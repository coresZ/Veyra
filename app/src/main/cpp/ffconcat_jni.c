/*
 * Veyra 手机端合并引擎：自写 JNI，直调 ffmpeg C API，1:1 复刻后端 server/downloader.js 的 concat 逻辑。
 *
 * Java 侧只调一个 native 函数 nativeConcat(paths[], outPath, tag, logPath)：
 *   - 成功返回 NULL（Java 收到 null）
 *   - 失败返回错误消息字符串（含失败阶段、rc、细节；Java 侧抛 IOException 显示详细日志）
 *   - 每执行一个阶段先写 native 阶段日志（logPath）：即使 native SIGSEGV 崩溃，也能从日志
 *     最后一行精确定位崩溃发生在哪一步
 *
 * 编译：NDK aarch64 clang + 静态链接 libavformat/libavcodec/libavutil（单 .so，无外部依赖）
 */
#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <signal.h>
#include <unistd.h>
#include <execinfo.h>
/* NDK execinfo.h 把 backtrace 标注为 API 33 引入，编译 target 21 时声明被隐藏导致隐式声明
 * （返回值按 int 截断指针）。现代设备（minSdk 26+）libc 均有该符号，手动声明消除风险。 */
extern int backtrace(void** buffer, int size);
extern char** backtrace_symbols(void* const* buffer, int size);
#include <libavformat/avformat.h>
#include <libavformat/avc.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

/* ---- native SIGSEGV/SIGABRT 捕获：崩溃时把 C 层 backtrace 写进阶段日志，再按默认行为终止 ----
 * Java 的 UncaughtExceptionHandler 抓不到 native 崩溃，这里在 nativeConcat 入口注册 handler，
 * 崩溃瞬间落盘函数栈，配合阶段日志即可精确定位崩溃点。 */
static char g_sigLogPath[1024] = {0};
static struct sigaction g_prevSegv, g_prevAbort;

static void crash_handler(int sig, siginfo_t* si, void* ctx) {
    FILE* f = fopen(g_sigLogPath, "a");
    if (f) {
        fprintf(f, "\n=== native 崩溃 signal=%d (%s) ===\n", sig,
                sig == SIGSEGV ? "SIGSEGV" : (sig == SIGABRT ? "SIGABRT" : "?"));
        void* bt[32];
        int n = backtrace(bt, 32);
        char** syms = backtrace_symbols(bt, n);
        for (int i = 0; i < n && i < 32; i++) {
            if (syms && syms[i]) fprintf(f, "  #%02d %s\n", i, syms[i]);
            else fprintf(f, "  #%02d 0x%lx\n", i, (unsigned long)bt[i]);
        }
        if (syms) free(syms);
        fflush(f);
        fclose(f);
    }
    /* 恢复默认 handler 后重抛，保持"闪退"行为（否则进程卡在 handler 里） */
    struct sigaction* prev = (sig == SIGSEGV) ? &g_prevSegv : &g_prevAbort;
    sigaction(sig, prev, NULL);
    raise(sig);
}

static void install_crash_handlers(const char* logPath) {
    if (logPath && *logPath) {
        snprintf(g_sigLogPath, sizeof(g_sigLogPath), "%s", logPath);
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = crash_handler;
        sa.sa_flags = SA_SIGINFO;
        sigaction(SIGSEGV, &sa, &g_prevSegv);
        sigaction(SIGABRT, &sa, &g_prevAbort);
    }
}

/* 输出上下文统一释放：先关 pb（已 avio_open），再 free context */
static void cleanup_output(AVFormatContext* oc) {
    if (!oc) return;
    if (oc->pb) avio_closep(&oc->pb);
    avformat_free_context(oc);
}

static char g_err[8192];
static int g_errLen = 0;

static void logErr(const char* fmt, ...) {
    if (g_errLen >= (int)sizeof(g_err) - 1) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(g_err + g_errLen, sizeof(g_err) - g_errLen, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    g_errLen += n;
    if (g_errLen >= (int)sizeof(g_err)) g_errLen = sizeof(g_err) - 1;
}

static void logStage(const char* stage) {
    logErr("失败阶段: %s\n", stage);
}

/* native 阶段日志：每阶段落盘一行，崩溃后 Java 侧可读最后一行定位崩溃点 */
static void stageLog(const char* logPath, const char* msg) {
    if (!logPath || !*logPath) return;
    FILE* f = fopen(logPath, "a");
    if (f) {
        fprintf(f, "%s\n", msg);
        fflush(f);
        fclose(f);
    }
}

/* 错误日志双写：既进 g_err（Java 侧汇总），也落盘 native.log（g_err 只保留末尾汇总，
 * 失败分步细节会丢失——transcode 内部失败点必须双写才能看到 -22 具体位置） */
static void logErrBoth(const char* logPath, const char* fmt, ...) {
    char tmp[1600];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    logErr("%s", tmp);
    stageLog(logPath, tmp);
}

static void logStageB(const char* logPath, const char* stage) {
    char tmp[128];
    snprintf(tmp, sizeof(tmp), "失败阶段: %s", stage);
    logStage(stage);
    stageLog(logPath, tmp);
}

static char* jstring_to_utf8(JNIEnv* env, jstring s, char* buf, int cap) {
    if (!s) return NULL;
    const char* c = (*env)->GetStringUTFChars(env, s, NULL);
    if (!c) return NULL;
    snprintf(buf, cap, "%s", c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return buf;
}

/* 与 ffmpeg CLI -tag:v xxx 等价的 fourcc 写入 */
static unsigned int make_tag(const char* t) {
    if (!t || strlen(t) != 4) return 0;
    return ((unsigned int)t[0]) | ((unsigned int)t[1] << 8) | ((unsigned int)t[2] << 16) | ((unsigned int)t[3] << 24);
}

/* 找到第一个视频流 + 第一个音频流（等价 -map 0:v:0 -map 0:a:0?） */
static void pick_streams(AVFormatContext* in, int* vIdx, int* aIdx) {
    *vIdx = -1; *aIdx = -1;
    for (int i = 0; i < (int)in->nb_streams; i++) {
        AVStream* st = in->streams[i];
        if (!st->codecpar) continue;
        if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && *vIdx < 0) *vIdx = i;
        else if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && *aIdx < 0) *aIdx = i;
    }
}

/* 从源上下文读包扫描，为视频/音频补 extradata：
 * TS 分片参数集（h264 SPS/PPS、AAC ADTS）不在开头时，mpegts demuxer 的 find_stream_info
 * 提取不到 → codecpar->extradata_size=0 → movenc write_header 写 avcC/esds 失败
 * （rc=-1094995529 Invalid data，v_extradata=0 a_extradata=0 即此场景）。
 * 扫描后构造标准 avcC / AudioSpecificConfig 写入 codecpar；随后 avio_seek 倒带，
 * 拷贝循环重读全部包（TS 本地文件 seek 安全，PTS 保持源值）。 */
static void scan_fill_extradata(AVFormatContext* in, int vIdx, int aIdx,
                                AVStream* vout, AVStream* aout) {
    if (vout->codecpar->extradata_size > 0 && aout->codecpar->extradata_size > 0) return;
    for (int k = 0; k < 256; k++) {
        AVPacket* pk = av_packet_alloc();
        if (!pk) break;
        if (av_read_frame(in, pk) < 0) { av_packet_free(&pk); break; }
        if (vout->codecpar->extradata_size == 0 && pk->stream_index == vIdx) {
            const uint8_t* d = pk->data; int n = pk->size;
            const uint8_t* sps = NULL; int spsLen = 0;
            const uint8_t* pps = NULL; int ppsLen = 0;
            int off = 0;
            while (off + 4 <= n) {
                int sc = -1;
                for (int i = off; i + 3 < n; i++) {
                    if (d[i] == 0 && d[i+1] == 0 && d[i+2] == 1) { sc = i + 3; break; }
                    if (i + 4 < n && d[i] == 0 && d[i+1] == 0 && d[i+2] == 0 && d[i+3] == 1) { sc = i + 4; break; }
                }
                if (sc < 0) break;
                int end = n;
                for (int i = sc; i + 3 < n; i++) {
                    if ((d[i] == 0 && d[i+1] == 0 && d[i+2] == 1) ||
                        (i + 4 < n && d[i] == 0 && d[i+1] == 0 && d[i+2] == 0 && d[i+3] == 1)) { end = i; break; }
                }
                int nalLen = end - sc;
                if (nalLen > 0) {
                    int type = d[sc] & 0x1F;
                    if (type == 7 && !sps) { sps = d + sc; spsLen = nalLen; }
                    else if (type == 8 && !pps) { pps = d + sc; ppsLen = nalLen; }
                }
                off = end;
                if (sps && pps) break;
            }
            if (sps && pps && spsLen >= 4) {
                int total = 11 + spsLen + ppsLen;
                uint8_t* avcc = (uint8_t*)av_mallocz(total + AV_INPUT_BUFFER_PADDING_SIZE);
                if (avcc) {
                    int p = 0;
                    avcc[p++] = 1; avcc[p++] = sps[1]; avcc[p++] = sps[2]; avcc[p++] = sps[3];
                    avcc[p++] = 0xFF; avcc[p++] = 0xE1;
                    avcc[p++] = (uint8_t)((spsLen >> 8) & 0xFF); avcc[p++] = (uint8_t)(spsLen & 0xFF);
                    memcpy(avcc + p, sps, spsLen); p += spsLen;
                    avcc[p++] = 1;
                    avcc[p++] = (uint8_t)((ppsLen >> 8) & 0xFF); avcc[p++] = (uint8_t)(ppsLen & 0xFF);
                    memcpy(avcc + p, pps, ppsLen); p += ppsLen;
                    vout->codecpar->extradata = avcc;
                    vout->codecpar->extradata_size = total;
                }
            }
        }
        /* 音频 ASC 不填：mpegts 音频样本为 raw AAC（无 ADTS 头），audio extradata=0 时
         * movenc 自动装 aac_adtstoasc bsf 透传；若填了 ASC，movenc 误判样本为 length-prefixed
         * 格式 → 写样本报 "Malformed AAC bitstream"（CLI 同款行为）。 */
        (void)aout; (void)aIdx;
        av_packet_free(&pk);
        if (vout->codecpar->extradata_size > 0 && aout->codecpar->extradata_size > 0) break;
    }
    if (in && in->pb) avio_seek(in->pb, 0, SEEK_SET);
}

/* 音频参数（等价后端 probeFile 的 audioKey 要素：codec|aProfile|sampleRate|layout） */
typedef struct {
    int codec;        /* AVCodecID */
    int profile;      /* AVCodecParameters.profile：区分 AAC-LC(2)/AAC-HE(SBR,5) 等 */
    int sample_rate;  /* Hz */
    int channels;     /* 声道数 */
    int count;        /* 出现次数（多数派统计） */
} AudioInfo;

/* 探测单个分集的音频参数；无音频轨时 codec=0（不计入多数派，与后端 filter(audioCodec) 一致） */
static void probe_audio_params(const char* path, const char* key, AudioInfo* ai) {
    ai->codec = 0; ai->profile = 0; ai->sample_rate = 0; ai->channels = 0;
    AVFormatContext* in = NULL;
    AVDictionary* opts = NULL;
    if (key && *key) av_dict_set(&opts, "decryption_key", key, 0);
    if (avformat_open_input(&in, path, NULL, &opts) < 0) { av_dict_free(&opts); return; }
    av_dict_free(&opts);
    if (avformat_find_stream_info(in, NULL) >= 0) {
        int v = -1, a = -1;
        pick_streams(in, &v, &a);
        if (a >= 0 && in->streams[a]->codecpar) {
            AVCodecParameters* cp = in->streams[a]->codecpar;
            ai->codec = cp->codec_id;
            ai->profile = cp->profile;
            ai->sample_rate = cp->sample_rate;
            ai->channels = (cp->ch_layout.nb_channels > 0) ? cp->ch_layout.nb_channels : cp->channels;
        }
    }
    avformat_close_input(&in);
}

/* 计算多数派音频参数（等价后端 buildPlan：majorityKey(audioKey)；无音频轨的集不参与） */
static void majority_audio(AudioInfo* infos, int n, AudioInfo* out) {
    AudioInfo uniq[64];
    int uniqN = 0;
    for (int i = 0; i < n; i++) {
        if (infos[i].codec <= 0 || infos[i].sample_rate <= 0) continue;   /* 无音频轨/探测失败不参与 */
        int found = -1;
        for (int j = 0; j < uniqN; j++) {
            if (uniq[j].codec == infos[i].codec &&
                uniq[j].profile == infos[i].profile &&
                uniq[j].sample_rate == infos[i].sample_rate &&
                uniq[j].channels == infos[i].channels) { found = j; break; }
        }
        if (found < 0) {
            if (uniqN >= 64) break;
            uniq[uniqN] = infos[i]; uniq[uniqN].count = 1; uniqN++;
        } else {
            uniq[found].count++;
        }
    }
    int best = -1, bestCnt = 0;
    for (int j = 0; j < uniqN; j++) {
        if (uniq[j].count > bestCnt) { bestCnt = uniq[j].count; best = j; }
    }
    if (best >= 0) {
        *out = uniq[best];
        /* 等价后端 majority.channels 兜底：无多数派时后端 fallback 2；这里给 2 声道默认 */
        if (out->channels <= 0) out->channels = 2;
        if (out->sample_rate <= 0) out->sample_rate = 44100;
        if (out->profile == 0) out->profile = FF_PROFILE_AAC_LOW;
    } else {
        out->codec = AV_CODEC_ID_AAC; out->profile = FF_PROFILE_AAC_LOW;
        out->sample_rate = 44100; out->channels = 2;
    }
    out->count = bestCnt;
}

/* 视频统一转码目标（等价后端 buildPlan 的"少数服从多数"）：
 * active=0 → 视频 -c copy（现状）；active=1 → 少数派集视频转码为 targetCodec（多数派方向）。 */
typedef struct {
    int active;       /* 0/1：h264+hevc 混装且需要统一时才置 1 */
    int targetCodec;  /* AV_CODEC_ID_H264 / AV_CODEC_ID_HEVC */
    int crf;          /* x264:18 / x265:21（视觉质量对齐） */
    int width;        /* 多数派代表集的宽（缩放到该尺寸 + 黑边 pad） */
    int height;
} VideoTarget;

/* 探测单个分集的视频编码与尺寸（多数派判定用）；失败时 codec=0 */
static void probe_video_codec(const char* path, const char* key, int* codec, int* w, int* h) {
    *codec = 0; *w = 0; *h = 0;
    AVFormatContext* in = NULL;
    AVDictionary* opts = NULL;
    if (key && *key) av_dict_set(&opts, "decryption_key", key, 0);
    if (avformat_open_input(&in, path, NULL, &opts) < 0) { av_dict_free(&opts); return; }
    av_dict_free(&opts);
    if (avformat_find_stream_info(in, NULL) >= 0) {
        int v = -1, a = -1;
        pick_streams(in, &v, &a);
        if (v >= 0 && in->streams[v]->codecpar) {
            AVCodecParameters* cp = in->streams[v]->codecpar;
            *codec = cp->codec_id;
            *w = cp->width; *h = cp->height;
        }
    }
    avformat_close_input(&in);
}

/* 阶段一：单集规范化（-c copy 重封装，时间轴归零 + dts 单调，等价后端"解密重封装"后的干净分集）。
 * key 非空时按 CENC-AES-CTR 解密（等价后端 ffmpeg -decryption_key，mov demuxer 原生支持），
 * 保持源帧率结构（CFR），避免 MediaMuxer 重封装引入 VFR 畸变（MPV 卡顿根因）。
 * 顺带校验：视频/音频轨道是否存在、音频包是否为空。返回 0 成功；<0 失败（错误已写日志）。 */
static int transcode_segment(const char* inPath, const char* outPath, int idx, const char* key,
                             const AudioInfo* tgt, const VideoTarget* vt, char* logBuf,
                             char* mimeV, size_t mimeVcap, char* mimeA, size_t mimeAcap,
                             int64_t* nVPkts, int64_t* nAPkts);
static int normalize_segment(const char* inPath, const char* outPath, int idx, const char* key,
                             const AudioInfo* tgt, const VideoTarget* vt, char* logBuf, char* mimeV, size_t mimeVcap,
                             char* mimeA, size_t mimeAcap,
                             int64_t* nVPkts, int64_t* nAPkts)
{
    *nVPkts = 0; *nAPkts = 0;
    if (mimeVcap > 0) mimeV[0] = 0;
    if (mimeAcap > 0) mimeA[0] = 0;

    AVFormatContext* in = NULL;
    AVDictionary* openOpts = NULL;
    if (key && *key) av_dict_set(&openOpts, "decryption_key", key, 0);
    int rc = avformat_open_input(&in, inPath, NULL, &openOpts);
    av_dict_free(&openOpts);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 avformat_open_input 失败 rc=%d (%s)\n", idx, rc, e);
        return rc;
    }
    rc = avformat_find_stream_info(in, NULL);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 find_stream_info 失败 rc=%d (%s)\n", idx, rc, e);
        avformat_close_input(&in); return rc;
    }
    int vIdx = -1, aIdx = -1;
    pick_streams(in, &vIdx, &aIdx);
    if (vIdx < 0) {
        logStage("规范化分集"); logErr("异常: 第%d集没有视频轨道\n", idx);
        avformat_close_input(&in); return AVERROR(EINVAL);
    }
    if (aIdx < 0) {
        logStage("规范化分集");
        logErr("异常: 第%d集没有音频轨道，合并后该段必然无声音；请重新下载第%d集\n", idx, idx);
        avformat_close_input(&in); return AVERROR(EINVAL);
    }
    /* 音频参数与多数派不一致 → 统一音轨转码（等价后端 buildPlan 的 step.audio：
     * ffmpeg -c:a aac -profile:a aac_low -b:a 192k -ar tgtSR -ac tgtCh）。
     * 判定含 profile：AAC-LC(2) vs AAC-HE/SBR(5) 帧长 1024 vs 2048，混流会触发解码切换错乱。
     * 这是产物音频格式混用导致播放器乱码/seek 丢声的修复关键。 */
    if (tgt && tgt->codec > 0) {
        AVCodecParameters* cp = in->streams[aIdx]->codecpar;
        int ch = (cp->ch_layout.nb_channels > 0) ? cp->ch_layout.nb_channels : cp->channels;
        if (cp->codec_id != tgt->codec || cp->profile != tgt->profile ||
            cp->sample_rate != tgt->sample_rate || ch != tgt->channels) {
            char wl[224];
            snprintf(wl, sizeof(wl), "[norm] 第%d集 音频参数不一致(本集 codec=%d prof=%d sr=%d ch=%d / 目标 codec=%d prof=%d sr=%d ch=%d) → 统一音轨转码",
                     idx, (int)cp->codec_id, (int)cp->profile, cp->sample_rate, ch,
                     tgt->codec, tgt->profile, tgt->sample_rate, tgt->channels);
            stageLog(logBuf, wl);
            int trc = transcode_segment(inPath, outPath, idx, key, tgt, vt, logBuf,
                                        mimeV, mimeVcap, mimeA, mimeAcap, nVPkts, nAPkts);
            if (trc == 0) return 0;
            /* 统一音轨转码失败 → 降级：原样 copy 重封装（保证合并成功）。该集音频为特殊 AAC 变体
             * （如 HE-AAC v2/SBR），自研 ffmpeg 解码器无法解码时保留原流，可能仍有局部兼容问题。 */
            char w2[256];
            snprintf(w2, sizeof(w2), "[norm] 第%d集 统一音轨转码失败 rc=%d → 降级原样保留该集音频（不做音频转码）",
                     idx, trc);
            stageLog(logBuf, w2);
            /* 继续执行下方纯 copy 规范化流程 */
        }
    }
    AVStream* vin = in->streams[vIdx];
    /* 视频编码与多数派不一致（h264/hevc 混装，少数服从多数）→ 视频统一转码。
     * 与音频判定共同走 transcode_segment（其内部同时处理视频+音频双通道）。
     * 视频混装不能降级：concat 把异编码包写进同一轨会花屏/seek 失败。 */
    if (vt && vt->active && vin->codecpar && vin->codecpar->codec_id != vt->targetCodec) {
        char wl[224];
        snprintf(wl, sizeof(wl), "[norm] 第%d集 视频编码与多数派不一致(本集 codec=%d / 目标 codec=%d) → 统一视频转码",
                 idx, (int)vin->codecpar->codec_id, vt->targetCodec);
        stageLog(logBuf, wl);
        int trc = transcode_segment(inPath, outPath, idx, key, tgt, vt, logBuf,
                                    mimeV, mimeVcap, mimeA, mimeAcap, nVPkts, nAPkts);
        if (trc == 0) return 0;
        char w2[256];
        snprintf(w2, sizeof(w2), "[norm] 第%d集 视频统一转码失败 rc=%d → 中止合并（视频编码混装硬合并会花屏）",
                 idx, trc);
        stageLog(logBuf, w2);
        return trc;
    }
    {
        const AVCodecDescriptor* cd = vin->codecpar ? avcodec_descriptor_get(vin->codecpar->codec_id) : NULL;
        if (cd && cd->name) snprintf(mimeV, mimeVcap, "%s", cd->name);
    }
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        const AVCodecDescriptor* cd = ain->codecpar ? avcodec_descriptor_get(ain->codecpar->codec_id) : NULL;
        if (cd && cd->name) snprintf(mimeA, mimeAcap, "%s", cd->name);
    }

    /* 输出上下文（mp4 muxer，copy，时间轴由本函数归零） */
    AVFormatContext* oc = NULL;
    rc = avformat_alloc_output_context2(&oc, NULL, "mp4", outPath);
    if (rc < 0 || !oc) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 输出上下文创建失败 rc=%d (%s)\n", idx, rc, e);
        avformat_close_input(&in); return rc ? rc : AVERROR(ENOMEM);
    }
    rc = avio_open(&oc->pb, outPath, AVIO_FLAG_WRITE);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 avio_open 失败 rc=%d (%s)\n", idx, rc, e);
        cleanup_output(oc); avformat_close_input(&in); return rc;
    }

    AVStream* vout = avformat_new_stream(oc, NULL);
    AVStream* aout = NULL;
    if (!vout) { logStage("规范化分集"); logErr("异常: 第%d集 输出视频流创建失败\n", idx); cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM); }
    avcodec_parameters_copy(vout->codecpar, vin->codecpar);
    vout->time_base = vin->time_base;
    /* movenc 需要 codec_tag：avc1/hvc1（movenc 的 codec_tag 表仅收录 avc1/hvc1，
     * avc3/hev1 被 validate_codec_tag 拒绝 → avformat_write_header 返回 INVALIDDATA
     * rc=-1094995529，即历轮 write_header 失败真凶）。参数集由 scan_fill_extradata
     * 补进 avcC（in-band 等价，播放兼容）。 */
    if (vout->codecpar->codec_id == AV_CODEC_ID_H264) vout->codecpar->codec_tag = MKTAG('a','v','c','1');
    else if (vout->codecpar->codec_id == AV_CODEC_ID_HEVC) vout->codecpar->codec_tag = MKTAG('h','v','c','1');
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        aout = avformat_new_stream(oc, NULL);
        if (!aout) { logStage("规范化分集"); logErr("异常: 第%d集 输出音频流创建失败\n", idx); cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM); }
        avcodec_parameters_copy(aout->codecpar, ain->codecpar);
        aout->codecpar->codec_tag = 0;  /* TS demuxer 会把 stream_type(0x0F) 塞进 codec_tag，mp4 muxer validate_codec_tag 拒绝 → INVALIDDATA；清 0 由 movenc 自动分配 mp4a */
        aout->time_base = ain->time_base;
    }

    /* TS 源参数集不在开头时 extradata=0 → movenc write_header 报 Invalid data：
     * 先读包扫描补 avcC/ASC，再写 header（扫描后已倒带，拷贝循环重读全部包） */
    scan_fill_extradata(in, vIdx, aIdx, vout, aout);

    AVDictionary* _mopts = NULL;
        av_dict_set(&_mopts, "movflags", "faststart+delay_moov", 0);
        rc = avformat_write_header(oc, &_mopts);
        av_dict_free(&_mopts);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 write_header 失败 rc=%d (%s) v_extradata=%d a_extradata=%d\n", idx, rc, e,
                vout->codecpar->extradata_size, aout ? aout->codecpar->extradata_size : -1);
        cleanup_output(oc); avformat_close_input(&in); return rc;
    }

    /* 拷贝循环：等价后端 ffmpeg -decryption_key K -i raw -c copy -f mp4——
     * 保持源时间戳与 edit list/AAC preroll 元数据原样，不做归零/单调化/帧间隔修正
     * （任何手动改动都会破坏源时间戳结构 → 音频边界乱码 / seek 后丢声）。
     * 跳过 concat demuxer 段边界产生的空包（size=0 / pts/dts=NOPTS）：空包仅作段切换
     * 标记，无样本数据；mov muxer 对 NOPTS 返回 EINVAL。若把空包写入产物，
     * 播放器会解码到 0 大小 AAC 帧 → 音频乱码 / 丢声。 */
    AVPacket* pkt = av_packet_alloc();
    if (!pkt) { logStage("规范化分集"); logErr("异常: 第%d集 av_packet_alloc 失败\n", idx); cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM); }
    /* 音频手动 adtstoasc：mpegts 输出的 AAC 样本带 ADTS 头，movenc 的 mov_check_bitstream
     * 只在首帧触发且条件苛刻（FFmpeg 6.1.1 对 ADTS 源直接报 "Malformed AAC bitstream"）。
     * 手动装 aac_adtstoasc bsf 剥头成 raw AAC（mp4 样本标准格式），movenc 不再触发报错。 */
    AVBSFContext* abf = NULL;
    if (aIdx >= 0) {
        const AVBitStreamFilter* bf = av_bsf_get_by_name("aac_adtstoasc");
        if (bf && av_bsf_alloc(bf, &abf) == 0) {
            avcodec_parameters_copy(abf->par_in, aout->codecpar);
            if (av_bsf_init(abf) < 0) { av_bsf_free(&abf); abf = NULL; }
        }
    }
    int readErr = 0;
    int64_t lastDtsV = AV_NOPTS_VALUE, lastDtsA = AV_NOPTS_VALUE;   /* 逐流 last dts（rescale 后单位） */
    int64_t firstDtsV = AV_NOPTS_VALUE, firstDtsA = AV_NOPTS_VALUE; /* 首包 dts（诊断） */
    while ((rc = av_read_frame(in, pkt)) >= 0) {
        int si = pkt->stream_index;
        int isV = (si == vIdx), isA = (si == aIdx);
        if (!isV && !isA) { av_packet_unref(pkt); continue; }
        if (pkt->size <= 0) { av_packet_unref(pkt); continue; }   /* 跳过空包 */
        AVStream* inSt = in->streams[si];
        AVStream* outSt = isV ? vout : aout;
        av_packet_rescale_ts(pkt, inSt->time_base, outSt->time_base);
        pkt->pos = -1;
        /* 视频样本 annex-b → length-prefixed：TS 源样本为 annex-b(start code)，movenc 对
         * 标准 avcC(0x01) 不做样本转换直接写 annex-b，导致 concat 的 h264_mp4toannexb bsf
         * 反向处理失败（"filter failed to receive output packet"→ 合并早期失败半成品）。
         * 用 libavformat 内部 ff_avc_parse_nal_units_buf（movenc 同款实现）转成标准 length-prefixed。 */
        if (isV) {
            uint8_t* nb = NULL;
            int nsz = 0;
            if (ff_avc_parse_nal_units_buf(pkt->data, &nb, &nsz) >= 0 && nsz > 0) {
                av_packet_unref(pkt);
                pkt->data = nb;
                pkt->size = nsz;
                pkt->buf = NULL;
            }
        }
        /* 等价后端 ffmpeg CLI 对 Non-monotonous DTS 的处理：CLI 的 mov muxer 默认
         * strict_std_compliance=NORMAL，遇 dts 重复/回退打印 warning 并 +1 修正（不报错）。
         * concat demuxer 在段边界会把下集首帧对齐到上集末帧（dts 重复），做同款 +1 修正，
         * 仅边界单帧微移（1 刻度 ≈ 22µs），与后端产物行为一致。 */
        int64_t* lastDts = isV ? &lastDtsV : &lastDtsA;
        if (pkt->dts == AV_NOPTS_VALUE) {
            pkt->dts = (*lastDts == AV_NOPTS_VALUE) ? 0 : *lastDts + 1;
        }
        if (pkt->pts == AV_NOPTS_VALUE) pkt->pts = pkt->dts;
        if (*lastDts != AV_NOPTS_VALUE && pkt->dts <= *lastDts) {
            pkt->dts = *lastDts + 1;
            if (pkt->pts < pkt->dts) pkt->pts = pkt->dts;
        }
        *lastDts = pkt->dts;
        if (isV) { if (firstDtsV == AV_NOPTS_VALUE) firstDtsV = pkt->dts; lastDtsV = pkt->dts; }
        else     { if (firstDtsA == AV_NOPTS_VALUE) firstDtsA = pkt->dts; lastDtsA = pkt->dts; }
        if (isA && abf) {
            av_bsf_send_packet(abf, pkt);
            av_packet_unref(pkt);
            AVPacket* op = av_packet_alloc();
            if (!op) { av_packet_free(&pkt); cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM); }
            while (av_bsf_receive_packet(abf, op) >= 0) {
                int orc = av_interleaved_write_frame(oc, op);
                if (orc < 0) {
                    char e[128] = {0}; av_strerror(orc, e, sizeof(e));
                    logStage("规范化分集"); logErr("异常: 第%d集 写包失败(音频bsf) rc=%d (%s) size=%d\n", idx, orc, e, op->size);
                    av_packet_free(&op); av_packet_free(&pkt); cleanup_output(oc); avformat_close_input(&in); if (abf) av_bsf_free(&abf); return orc;
                }
                (*nAPkts)++;
                av_packet_unref(op);
            }
            av_packet_free(&op);
            continue;
        }
        rc = av_interleaved_write_frame(oc, pkt);
        int failRc = rc, failSize = pkt->size;
        long long failPts = pkt->pts, failDts = pkt->dts;
        av_packet_unref(pkt);
        if (failRc < 0) {
            char e[128] = {0}; av_strerror(failRc, e, sizeof(e));
            logStage("规范化分集"); logErr("异常: 第%d集 写包失败 rc=%d (%s)，已写 视频包=%lld 音频包=%lld；包信息: stream=%d(%s) pts=%lld dts=%lld size=%d\n",
                    idx, failRc, e, (long long)*nVPkts, (long long)*nAPkts, si, isV ? "video" : "audio", failPts, failDts, failSize);
            av_packet_free(&pkt); cleanup_output(oc); avformat_close_input(&in); return failRc;
        }
        if (isV) (*nVPkts)++; else (*nAPkts)++;
    }
    readErr = rc;
    /* 音视频轨诊断（定位乱码/丢声用）：首末包时间戳、流参数、平均帧间隔 */
    {
        char dl[384];
        if (aIdx >= 0 && *nAPkts > 0) {
            AVStream* ain = in->streams[aIdx];
            const char* sf = av_get_sample_fmt_name(ain->codecpar->format);
            snprintf(dl, sizeof(dl),
                     "[norm] 第%d集 音频 tb=%d/%d sr=%d ch=%d fmt=%s 首dts=%lld 末dts=%lld 平均间隔=%lld 包=%lld",
                     idx, ain->time_base.num, ain->time_base.den,
                     ain->codecpar->sample_rate,
                     (ain->codecpar->ch_layout.nb_channels > 0) ? ain->codecpar->ch_layout.nb_channels : ain->codecpar->channels,
                     sf ? sf : "?", (long long)firstDtsA, (long long)lastDtsA,
                     (long long)((*nAPkts > 1) ? (lastDtsA - firstDtsA) / (*nAPkts - 1) : 0),
                     (long long)*nAPkts);
            stageLog(logBuf, dl);
        }
        if (*nVPkts > 0) {
            AVStream* vin2 = in->streams[vIdx];
            snprintf(dl, sizeof(dl),
                     "[norm] 第%d集 视频 tb=%d/%d 首dts=%lld 末dts=%lld 平均间隔=%lld 包=%lld",
                     idx, vin2->time_base.num, vin2->time_base.den,
                     (long long)firstDtsV, (long long)lastDtsV,
                     (long long)((*nVPkts > 1) ? (lastDtsV - firstDtsV) / (*nVPkts - 1) : 0),
                     (long long)*nVPkts);
            stageLog(logBuf, dl);
        }
    }
    rc = av_write_trailer(oc);
    av_packet_free(&pkt);
    if (abf) av_bsf_free(&abf);
    cleanup_output(oc);
    avformat_close_input(&in);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStage("规范化分集"); logErr("异常: 第%d集 write_trailer 失败 rc=%d (%s)\n", idx, rc, e);
        return rc;
    }
    /* 音频轨道存在但 0 包：说明该集音频损坏/截断，合并出来必然中间无声 → 明确报错 */
    if (aIdx >= 0 && *nAPkts <= 0) {
        logStage("规范化分集");
        logErr("异常: 第%d集音频轨道为空(0 个音频包)，该集音频可能损坏，合并后中间会没有声音；请重新下载第%d集\n", idx, idx);
        return AVERROR(EINVAL);
    }
    if (*nVPkts <= 0) {
        logStage("规范化分集"); logErr("异常: 第%d集视频轨道为空(0 个视频包)\n", idx);
        return AVERROR(EINVAL);
    }
    (void)readErr;
    return 0;
}

/* 阶段一-b：单集音频统一转码（等价后端"统一音轨" ffmpeg -c:a aac -profile:a aac_low -b:a 192k -ar SR -ac CH）。
 * 本集音频参数（codec/采样率/声道）与多数派不一致时调用：音频 解码→swr→aac 编码，视频 -c copy。
 * 用于修复产物中音轨声道/采样率混用 → 播放器解码切换错乱（乱码/seek 丢声）的根源。 */
static int transcode_segment(const char* inPath, const char* outPath, int idx, const char* key,
                             const AudioInfo* tgt, const VideoTarget* vt, char* logBuf,
                             char* mimeV, size_t mimeVcap, char* mimeA, size_t mimeAcap,
                             int64_t* nVPkts, int64_t* nAPkts)
{
    *nVPkts = 0; *nAPkts = 0;
    if (mimeVcap > 0) mimeV[0] = 0;
    if (mimeAcap > 0) { snprintf(mimeA, mimeAcap, "aac"); }

    int doVideo = (vt && vt->active) ? 1 : 0;   /* 视频也统一转码（少数派 → 多数派编码） */

    AVFormatContext* in = NULL;
    AVDictionary* openOpts = NULL;
    if (key && *key) av_dict_set(&openOpts, "decryption_key", key, 0);
    int rc = avformat_open_input(&in, inPath, NULL, &openOpts);
    av_dict_free(&openOpts);
    if (rc < 0) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 avformat_open_input 失败 rc=%d\n", idx, rc); return rc; }
    if (avformat_find_stream_info(in, NULL) < 0) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 find_stream_info 失败\n", idx); avformat_close_input(&in); return AVERROR(EINVAL); }
    int vIdx = -1, aIdx = -1;
    pick_streams(in, &vIdx, &aIdx);
    if (vIdx < 0 || aIdx < 0) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 无视频或音频轨道\n", idx); avformat_close_input(&in); return AVERROR(EINVAL); }
    AVStream* vin = in->streams[vIdx];
    AVStream* ain = in->streams[aIdx];
    {
        const AVCodecDescriptor* cd = vin->codecpar ? avcodec_descriptor_get(vin->codecpar->codec_id) : NULL;
        if (cd && cd->name && mimeVcap > 0) snprintf(mimeV, mimeVcap, "%s", cd->name);
        /* 视频转码时 mimeV 按目标编码上报（供主流程 tag 统计多数派方向） */
        if (doVideo && mimeVcap > 0)
            snprintf(mimeV, mimeVcap, "%s", vt->targetCodec == AV_CODEC_ID_HEVC ? "hevc" : "h264");
    }

    /* 解码器（音频） */
    const AVCodec* dec = avcodec_find_decoder(ain->codecpar->codec_id);
    if (!dec) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 找不到音频解码器 codec=%d\n", idx, ain->codecpar->codec_id); avformat_close_input(&in); return AVERROR(ENOSYS); }
    AVCodecContext* decCtx = avcodec_alloc_context3(dec);
    if (!decCtx || avcodec_parameters_to_context(decCtx, ain->codecpar) < 0 || avcodec_open2(decCtx, dec, NULL) < 0) {
        logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 音频解码器打开失败\n", idx);
        if (decCtx) avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(EINVAL);
    }

    /* 视频解码器（仅视频转码时） */
    AVCodecContext* vdecCtx = NULL;
    if (doVideo) {
        const AVCodec* vdec = avcodec_find_decoder(vin->codecpar->codec_id);
        if (!vdec) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 找不到视频解码器 codec=%d\n", idx, vin->codecpar->codec_id);
            avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(ENOSYS);
        }
        vdecCtx = avcodec_alloc_context3(vdec);
        if (!vdecCtx || avcodec_parameters_to_context(vdecCtx, vin->codecpar) < 0 || avcodec_open2(vdecCtx, vdec, NULL) < 0) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 视频解码器打开失败\n", idx);
            if (vdecCtx) avcodec_free_context(&vdecCtx);
            avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(EINVAL);
        }
    }

    /* 输出上下文 */
    AVFormatContext* oc = NULL;
    rc = avformat_alloc_output_context2(&oc, NULL, "mp4", outPath);
    if (rc < 0 || !oc) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 输出上下文创建失败 rc=%d\n", idx, rc); avcodec_free_context(&decCtx); avformat_close_input(&in); return rc ? rc : AVERROR(ENOMEM); }
    rc = avio_open(&oc->pb, outPath, AVIO_FLAG_WRITE);
    if (rc < 0) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 avio_open 失败 rc=%d\n", idx, rc); cleanup_output(oc); avcodec_free_context(&decCtx); avformat_close_input(&in); return rc; }

    AVStream* vout = avformat_new_stream(oc, NULL);
    if (!vout) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 输出视频流创建失败\n", idx); cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(ENOMEM); }

    /* 视频编码器 + 缩放/pad（仅视频统一转码时） */
    AVCodecContext* vencCtx = NULL;
    struct SwsContext* sws = NULL;
    AVFrame* fit = NULL;
    AVFrame* pv = NULL;
    int fitW = 0, fitH = 0;
    if (doVideo) {
        const char* encName = (vt->targetCodec == AV_CODEC_ID_HEVC) ? "libx265" : "libx264";
        const AVCodec* venc = avcodec_find_encoder_by_name(encName);
        if (!venc) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 找不到视频编码器 %s（工具链未编译）\n", idx, encName);
            cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(ENOSYS);
        }
        vencCtx = avcodec_alloc_context3(venc);
        if (!vencCtx) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 视频编码器上下文分配失败\n", idx);
            cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(ENOMEM);
        }
        vencCtx->width = vt->width; vencCtx->height = vt->height;
        vencCtx->pix_fmt = AV_PIX_FMT_YUV420P;
        vencCtx->gop_size = 250;
        vencCtx->max_b_frames = 4;
        vencCtx->thread_count = 4;
        {
            AVRational fr = vin->avg_frame_rate;
            AVRational tb = {1, 25};
            if (fr.num > 0 && fr.den > 0 && fr.num >= fr.den)
                av_reduce(&tb.num, &tb.den, fr.den, fr.num, 1000000);
            vencCtx->time_base = tb;
        }
        char crf[8]; snprintf(crf, sizeof(crf), "%d", vt->crf);
        av_opt_set(vencCtx->priv_data, "preset", "veryfast", 0);
        av_opt_set(vencCtx->priv_data, "crf", crf, 0);
        if (vt->targetCodec == AV_CODEC_ID_H264) av_opt_set(vencCtx->priv_data, "profile", "high", 0);
        if (avcodec_open2(vencCtx, venc, NULL) < 0) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 视频编码器 %s 打开失败\n", idx, encName);
            avcodec_free_context(&vencCtx); cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(EINVAL);
        }
        {
            int sw = vdecCtx->width, sh = vdecCtx->height;
            if (sw > 0 && sh > 0 && vt->width > 0 && vt->height > 0) {
                if ((int64_t)sw * vt->height > (int64_t)sh * vt->width) {
                    fitH = ((int)((int64_t)sh * vt->width / sw)) & ~1;
                    fitW = vt->width;
                } else {
                    fitW = ((int)((int64_t)sw * vt->height / sh)) & ~1;
                    fitH = vt->height;
                }
                if (fitW < 2) fitW = 2; if (fitH < 2) fitH = 2;
                sws = sws_getContext(sw, sh, vdecCtx->pix_fmt, fitW, fitH, AV_PIX_FMT_YUV420P, SWS_BILINEAR, NULL, NULL, NULL);
                fit = av_frame_alloc();
                if (sws && fit) {
                    fit->format = AV_PIX_FMT_YUV420P; fit->width = fitW; fit->height = fitH;
                    if (av_frame_get_buffer(fit, 32) < 0) { av_frame_free(&fit); fit = NULL; }
                }
                pv = av_frame_alloc();
                if (pv) {
                    pv->format = AV_PIX_FMT_YUV420P; pv->width = vt->width; pv->height = vt->height;
                    if (av_frame_get_buffer(pv, 32) < 0) { av_frame_free(&pv); pv = NULL; }
                }
                if (!sws || !fit || !pv) {
                    logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 视频 sws/pad 初始化失败\n", idx);
                    if (sws) sws_freeContext(&sws); if (fit) av_frame_free(&fit); if (pv) av_frame_free(&pv);
                    avcodec_free_context(&vencCtx); cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(ENOMEM);
                }
            }
        }
        avcodec_parameters_from_context(vout->codecpar, vencCtx);
        vout->codecpar->codec_tag = make_tag(vt->targetCodec == AV_CODEC_ID_HEVC ? "hvc1" : "avc1");
        vout->time_base = vencCtx->time_base;
    } else {
        avcodec_parameters_copy(vout->codecpar, vin->codecpar);
        vout->time_base = vin->time_base;
    }

    AVStream* aout = avformat_new_stream(oc, NULL);
    if (!aout) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 输出音频流创建失败\n", idx); cleanup_output(oc); avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(ENOMEM); }

    /* AAC 编码器（-profile:a aac_low -b:a 192k -ar tgtSR -ac tgtCh） */
    const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
    if (!enc) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 无 AAC 编码器（工具链未编译 encoder）\n", idx); cleanup_output(oc); avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(ENOSYS); }
    AVCodecContext* encCtx = avcodec_alloc_context3(enc);
    if (!encCtx) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 编码器上下文分配失败\n", idx); cleanup_output(oc); avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(ENOMEM); }
    encCtx->sample_rate = tgt->sample_rate;
    av_channel_layout_default(&encCtx->ch_layout, tgt->channels);
    encCtx->bit_rate = 192000;
    encCtx->profile = FF_PROFILE_AAC_LOW;
    encCtx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    encCtx->time_base = (AVRational){1, tgt->sample_rate};
    if (avcodec_open2(encCtx, enc, NULL) < 0) {
        logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 AAC 编码器打开失败\n", idx);
        avcodec_free_context(&encCtx); cleanup_output(oc); avcodec_free_context(&decCtx); avformat_close_input(&in); return AVERROR(EINVAL);
    }
    avcodec_parameters_from_context(aout->codecpar, encCtx);   /* aac + target 参数 + esds */
    aout->codecpar->codec_tag = 0;  /* 防污染 tag 校验 */
    aout->time_base = (AVRational){1, tgt->sample_rate};

    /* 重采样（声道/采样率转换） */
    SwrContext* swr = NULL;
    {
        AVChannelLayout inCh = decCtx->ch_layout, outCh = encCtx->ch_layout;
        if (swr_alloc_set_opts2(&swr, &outCh, AV_SAMPLE_FMT_FLTP, encCtx->sample_rate,
                                &inCh, decCtx->sample_fmt, decCtx->sample_rate, 0, NULL) < 0 || !swr || swr_init(swr) < 0) {
            logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 swr 初始化失败\n", idx);
            if (swr) swr_free(&swr); avcodec_free_context(&encCtx); if (vencCtx) avcodec_free_context(&vencCtx);
            if (sws) sws_freeContext(&sws); if (fit) av_frame_free(&fit); if (pv) av_frame_free(&pv);
            cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return AVERROR(EINVAL);
        }
    }

    AVDictionary* _mopts = NULL;
        av_dict_set(&_mopts, "movflags", "faststart+delay_moov", 0);
        rc = avformat_write_header(oc, &_mopts);
        av_dict_free(&_mopts);
    if (rc < 0) { logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 write_header 失败 rc=%d\n", idx, rc); swr_free(&swr); avcodec_free_context(&encCtx); if (vencCtx) avcodec_free_context(&vencCtx); if (sws) sws_freeContext(&sws); if (fit) av_frame_free(&fit); if (pv) av_frame_free(&pv); cleanup_output(oc); avcodec_free_context(&decCtx); if (vdecCtx) avcodec_free_context(&vdecCtx); avformat_close_input(&in); return rc; }

    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    AVFrame* ofr = av_frame_alloc();
    int64_t lastDtsV = AV_NOPTS_VALUE;
    int64_t audioPts = 0;
    int64_t vFrames = 0;
    int loopRc = 0;
    while ((loopRc = av_read_frame(in, pkt)) >= 0) {
        int si = pkt->stream_index;
        if (si == vIdx) {
            if (doVideo && vdecCtx && vencCtx && sws && fit && pv) {
                /* 视频统一转码：解码 → 缩放+黑边 pad(yuv420p) → libx264/libx265 编码 → 写 */
                int ser = avcodec_send_packet(vdecCtx, pkt);
                av_packet_unref(pkt);
                if (ser < 0 && ser != AVERROR_EOF) {
                    char e[128] = {0}; av_strerror(ser, e, sizeof(e));
                    logStageB(logBuf, "规范化分集");
                    logErrBoth(logBuf, "异常: 第%d集 视频 avcodec_send_packet 失败 rc=%d (%s)，已写 视频包=%lld 音频包=%lld\n",
                           idx, ser, e, (long long)*nVPkts, (long long)*nAPkts);
                    loopRc = ser; break;
                }
                while (avcodec_receive_frame(vdecCtx, frame) >= 0) {
                    sws_scale(sws, frame->data, frame->linesize, 0, vdecCtx->height, fit->data, fit->linesize);
                    av_frame_make_writable(pv);
                    memset(pv->data[0], 16, (size_t)pv->linesize[0] * pv->height);
                    memset(pv->data[1], 128, (size_t)pv->linesize[1] * (pv->height / 2));
                    memset(pv->data[2], 128, (size_t)pv->linesize[2] * (pv->height / 2));
                    int px = (vt->width - fitW) / 2, py = (vt->height - fitH) / 2;
                    for (int y = 0; y < fitH; y++)
                        memcpy(pv->data[0] + (size_t)(py + y) * pv->linesize[0] + px,
                               fit->data[0] + (size_t)y * fit->linesize[0], (size_t)fitW);
                    for (int y = 0; y < fitH / 2; y++)
                        memcpy(pv->data[1] + (size_t)(py / 2 + y) * pv->linesize[1] + px / 2,
                               fit->data[1] + (size_t)y * fit->linesize[1], (size_t)fitW / 2);
                    for (int y = 0; y < fitH / 2; y++)
                        memcpy(pv->data[2] + (size_t)(py / 2 + y) * pv->linesize[2] + px / 2,
                               fit->data[2] + (size_t)y * fit->linesize[2], (size_t)fitW / 2);
                    if (frame->pts != AV_NOPTS_VALUE)
                        pv->pts = av_rescale_q(frame->pts, vdecCtx->time_base, vencCtx->time_base);
                    else
                        pv->pts = vFrames;
                    int es = avcodec_send_frame(vencCtx, pv);
                    if (es < 0) {
                        char e[128] = {0}; av_strerror(es, e, sizeof(e));
                        logStageB(logBuf, "规范化分集");
                        logErrBoth(logBuf, "异常: 第%d集 视频 avcodec_send_frame 失败 rc=%d (%s)，帧 pts=%lld\n",
                                   idx, es, e, (long long)pv->pts);
                        loopRc = es; break;
                    }
                    AVPacket* vp = av_packet_alloc();
                    while (avcodec_receive_packet(vencCtx, vp) >= 0) {
                        vp->stream_index = vout->index;
                        vp->pos = -1;
                        int wr = av_interleaved_write_frame(oc, vp);
                        if (wr < 0) {
                            char e[128] = {0}; av_strerror(wr, e, sizeof(e));
                            long long wp = (long long)vp->pts;
                            av_packet_unref(vp);
                            logStageB(logBuf, "规范化分集");
                            logErrBoth(logBuf, "异常: 第%d集 视频 写包失败 rc=%d (%s)，pts=%lld 已写 视频包=%lld\n",
                                       idx, wr, e, wp, (long long)*nVPkts);
                            loopRc = wr; break;
                        }
                        av_packet_unref(vp);
                        (*nVPkts)++;
                    }
                    av_packet_free(&vp);
                    if (loopRc < 0) break;
                    vFrames++;
                }
                av_frame_unref(frame);
                if (loopRc < 0) break;
            } else {
                av_packet_rescale_ts(pkt, vin->time_base, vout->time_base);
                pkt->pos = -1;
                if (pkt->dts == AV_NOPTS_VALUE) pkt->dts = (lastDtsV == AV_NOPTS_VALUE) ? 0 : lastDtsV + 1;
                if (pkt->pts == AV_NOPTS_VALUE) pkt->pts = pkt->dts;
                if (lastDtsV != AV_NOPTS_VALUE && pkt->dts <= lastDtsV) { pkt->dts = lastDtsV + 1; if (pkt->pts < pkt->dts) pkt->pts = pkt->dts; }
                lastDtsV = pkt->dts;
                int wr = av_interleaved_write_frame(oc, pkt);
                av_packet_unref(pkt);
                if (wr < 0) { loopRc = wr; break; }
                (*nVPkts)++;
            }
        } else if (si == aIdx) {
            int ser = avcodec_send_packet(decCtx, pkt);
            av_packet_unref(pkt);
            if (ser < 0 && ser != AVERROR_EOF) {
                char e[128] = {0}; av_strerror(ser, e, sizeof(e));
                logStageB(logBuf, "规范化分集");
                logErrBoth(logBuf, "异常: 第%d集 音频 avcodec_send_packet 失败 rc=%d (%s)，已写 视频包=%lld 音频包=%lld\n",
                       idx, ser, e, (long long)*nVPkts, (long long)*nAPkts);
                loopRc = ser; break;
            }
            int rcv = avcodec_receive_frame(decCtx, frame);
            if (rcv >= 0) {
                int outN = av_rescale_rnd(swr_get_delay(swr, decCtx->sample_rate) + frame->nb_samples,
                                          encCtx->sample_rate, decCtx->sample_rate, AV_ROUND_UP);
                av_frame_unref(ofr);
                ofr->format = AV_SAMPLE_FMT_FLTP;
                ofr->sample_rate = encCtx->sample_rate;
                av_channel_layout_copy(&ofr->ch_layout, &encCtx->ch_layout);
                ofr->nb_samples = outN;
                if (av_frame_get_buffer(ofr, 0) < 0) {
                    logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 音频 输出帧缓冲分配失败\n", idx);
                    loopRc = AVERROR(ENOMEM); break;
                }
                int conv = swr_convert(swr, ofr->data, outN, (const uint8_t**)frame->data, frame->nb_samples);
                if (conv < 0) {
                    char e[128] = {0}; av_strerror(conv, e, sizeof(e));
                    logStageB(logBuf, "规范化分集");
                    logErrBoth(logBuf, "异常: 第%d集 音频 swr_convert 失败 rc=%d (%s)，帧 nb_samples=%d 解码 sr=%d ch=%d / 目标 sr=%d ch=%d\n",
                           idx, conv, e, frame->nb_samples, decCtx->sample_rate,
                           decCtx->ch_layout.nb_channels, encCtx->sample_rate, encCtx->ch_layout.nb_channels);
                    loopRc = conv; break;
                }
                ofr->nb_samples = conv;
                /* AAC 编码器固定 1024 样本/帧：swr 升采样（如 22050→44100 为 2×）一次可能产出
                 * 2048 样本，必须切成 1024 块逐帧送编码器，否则 avcodec_send_frame 报 -22。 */
                {
                    int consumed = 0;
                    while (conv - consumed >= 1024) {
                        AVFrame* sub = av_frame_alloc();
                        if (!sub) { loopRc = AVERROR(ENOMEM); break; }
                        sub->format = ofr->format;
                        sub->sample_rate = ofr->sample_rate;
                        av_channel_layout_copy(&sub->ch_layout, &ofr->ch_layout);
                        sub->nb_samples = 1024;
                        if (av_frame_get_buffer(sub, 0) < 0) {
                            av_frame_free(&sub); loopRc = AVERROR(ENOMEM); break;
                        }
                        /* FLTP planar：每声道一个平面，float32 样本 */
                        for (int pl = 0; pl < ofr->ch_layout.nb_channels; pl++)
                            memcpy(sub->data[pl], ofr->data[pl] + (size_t)consumed * 4, 1024 * 4);
                        sub->pts = audioPts + consumed;
                        int es = avcodec_send_frame(encCtx, sub);
                        av_frame_free(&sub);
                        if (es < 0) {
                            char e[128] = {0}; av_strerror(es, e, sizeof(e));
                            logStageB(logBuf, "规范化分集");
                            logErrBoth(logBuf, "异常: 第%d集 音频 avcodec_send_frame 失败 rc=%d (%s)，subPts=%lld\n",
                                       idx, es, e, (long long)(audioPts + consumed));
                            loopRc = es; break;
                        }
                        AVPacket* ep = av_packet_alloc();
                        while (avcodec_receive_packet(encCtx, ep) >= 0) {
                            av_packet_rescale_ts(ep, encCtx->time_base, aout->time_base);
                            ep->pos = -1;
                            /* encoder 输出的 packet stream_index 未设置（默认 0=视频流），
                             * 必须指到音频输出流，否则 muxer 把 AAC 包写进视频流报 -22 */
                            ep->stream_index = aout->index;
                            int wr = av_interleaved_write_frame(oc, ep);
                            if (wr < 0) {
                                char e[128] = {0}; av_strerror(wr, e, sizeof(e));
                                long long wp = (long long)ep->pts;
                                av_packet_unref(ep);
                                logStageB(logBuf, "规范化分集");
                                logErrBoth(logBuf, "异常: 第%d集 音频 写包失败 rc=%d (%s)，pts=%lld 已写 音频包=%lld\n",
                                           idx, wr, e, wp, (long long)*nAPkts);
                                loopRc = wr; break;
                            }
                            av_packet_unref(ep);
                            (*nAPkts)++;
                        }
                        av_packet_free(&ep);
                        if (loopRc < 0) break;
                        consumed += 1024;
                    }
                    audioPts += conv;   /* 44100 基准采样计数推进 */
                }
                if (loopRc < 0) break;
            } else if (rcv != AVERROR(EAGAIN) && rcv != AVERROR_EOF) {
                char e[128] = {0}; av_strerror(rcv, e, sizeof(e));
                logStageB(logBuf, "规范化分集");
                logErrBoth(logBuf, "异常: 第%d集 音频 avcodec_receive_frame 失败 rc=%d (%s)\n", idx, rcv, e);
                loopRc = rcv; break;
            }
            av_frame_unref(frame);
            if (loopRc < 0) break;
        } else {
            av_packet_unref(pkt);
        }
    }
    /* 编码器 flush */
    if (loopRc >= 0 || loopRc == AVERROR_EOF) {
        avcodec_send_frame(encCtx, NULL);
        AVPacket* ep = av_packet_alloc();
        while (avcodec_receive_packet(encCtx, ep) >= 0) {
            av_packet_rescale_ts(ep, encCtx->time_base, aout->time_base);
            ep->pos = -1;
            ep->stream_index = aout->index;
            int wr = av_interleaved_write_frame(oc, ep);
            if (wr < 0) {
                char e[128] = {0}; av_strerror(wr, e, sizeof(e));
                long long wp = (long long)ep->pts;
                av_packet_unref(ep);
                logStageB(logBuf, "规范化分集");
                logErrBoth(logBuf, "异常: 第%d集 音频 flush 写包失败 rc=%d (%s)，pts=%lld\n", idx, wr, e, wp);
                loopRc = wr; break;
            }
            av_packet_unref(ep);
            (*nAPkts)++;
        }
        av_packet_free(&ep);
        /* 视频 flush：先 drain 解码器剩余帧（送入编码），再 flush 编码器 */
        if (doVideo && vdecCtx && vencCtx && sws && fit && pv) {
            avcodec_send_packet(vdecCtx, NULL);
            while (avcodec_receive_frame(vdecCtx, frame) >= 0) {
                sws_scale(sws, frame->data, frame->linesize, 0, vdecCtx->height, fit->data, fit->linesize);
                av_frame_make_writable(pv);
                memset(pv->data[0], 16, (size_t)pv->linesize[0] * pv->height);
                memset(pv->data[1], 128, (size_t)pv->linesize[1] * (pv->height / 2));
                memset(pv->data[2], 128, (size_t)pv->linesize[2] * (pv->height / 2));
                int px = (vt->width - fitW) / 2, py = (vt->height - fitH) / 2;
                for (int y = 0; y < fitH; y++)
                    memcpy(pv->data[0] + (size_t)(py + y) * pv->linesize[0] + px,
                           fit->data[0] + (size_t)y * fit->linesize[0], (size_t)fitW);
                for (int y = 0; y < fitH / 2; y++)
                    memcpy(pv->data[1] + (size_t)(py / 2 + y) * pv->linesize[1] + px / 2,
                           fit->data[1] + (size_t)y * fit->linesize[1], (size_t)fitW / 2);
                for (int y = 0; y < fitH / 2; y++)
                    memcpy(pv->data[2] + (size_t)(py / 2 + y) * pv->linesize[2] + px / 2,
                           fit->data[2] + (size_t)y * fit->linesize[2], (size_t)fitW / 2);
                if (frame->pts != AV_NOPTS_VALUE)
                    pv->pts = av_rescale_q(frame->pts, vdecCtx->time_base, vencCtx->time_base);
                else
                    pv->pts = vFrames;
                avcodec_send_frame(vencCtx, pv);
                vFrames++;
            }
            avcodec_send_frame(vencCtx, NULL);
            AVPacket* vp = av_packet_alloc();
            while (avcodec_receive_packet(vencCtx, vp) >= 0) {
                vp->stream_index = vout->index;
                vp->pos = -1;
                int wr = av_interleaved_write_frame(oc, vp);
                if (wr < 0) {
                    char e[128] = {0}; av_strerror(wr, e, sizeof(e));
                    long long wp = (long long)vp->pts;
                    av_packet_unref(vp);
                    logStageB(logBuf, "规范化分集");
                    logErrBoth(logBuf, "异常: 第%d集 视频 flush 写包失败 rc=%d (%s)，pts=%lld\n", idx, wr, e, wp);
                    loopRc = wr; break;
                }
                av_packet_unref(vp);
                (*nVPkts)++;
            }
            av_packet_free(&vp);
        }
    }
    av_packet_free(&pkt);
    av_frame_free(&frame);
    av_frame_free(&ofr);
    rc = av_write_trailer(oc);
    swr_free(&swr);
    avcodec_free_context(&encCtx);
    if (vencCtx) avcodec_free_context(&vencCtx);
    if (vdecCtx) avcodec_free_context(&vdecCtx);
    if (sws) sws_freeContext(&sws);
    if (fit) av_frame_free(&fit);
    if (pv) av_frame_free(&pv);
    avcodec_free_context(&decCtx);
    cleanup_output(oc);
    avformat_close_input(&in);
    if (loopRc < 0 && loopRc != AVERROR_EOF) {
        char e[128] = {0}; av_strerror(loopRc, e, sizeof(e));
        logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 转码失败 rc=%d (%s)，已写 视频包=%lld 音频包=%lld\n", idx, loopRc, e, (long long)*nVPkts, (long long)*nAPkts);
        return loopRc;
    }
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "规范化分集"); logErrBoth(logBuf, "异常: 第%d集 write_trailer 失败 rc=%d (%s)\n", idx, rc, e);
        return rc;
    }
    if (*nAPkts <= 0) {
        logStageB(logBuf, "规范化分集");
        logErrBoth(logBuf, "异常: 第%d集音频转码后为空(0 个音频包)，该集音频可能损坏；请重新下载第%d集\n", idx, idx);
        return AVERROR(EINVAL);
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_veyra_gybeyond_FFmpegConcat_nativeConcat(JNIEnv* env, jclass cls,
                                                  jobjectArray inputPaths,
                                                  jobjectArray keys,
                                                  jstring outputPath,
                                                  jstring tag,
                                                  jstring logPath)
{
    g_err[0] = 0; g_errLen = 0;

    char logBuf[1024];
    if (!jstring_to_utf8(env, logPath, logBuf, sizeof(logBuf))) logBuf[0] = 0;
    install_crash_handlers(logBuf);
    stageLog(logBuf, "[nativeConcat] 进入 native 合并");

    int n = inputPaths ? (*env)->GetArrayLength(env, inputPaths) : 0;
    if (n <= 0) { stageLog(logBuf, "[nativeConcat] 失败: 分集数=0"); logStage("参数校验"); logErr("异常: 分集数=%d，无法合并\n", n); return (*env)->NewStringUTF(env, g_err); }
    if (n > 256) { stageLog(logBuf, "[nativeConcat] 失败: 分集数超上限"); logStage("参数校验"); logErr("异常: 分集数=%d 超过 256 上限\n", n); return (*env)->NewStringUTF(env, g_err); }
    stageLog(logBuf, "[nativeConcat] 分集数 = N 正确");

    char outBuf[1024];
    if (!jstring_to_utf8(env, outputPath, outBuf, sizeof(outBuf))) {
        stageLog(logBuf, "[nativeConcat] 失败: 输出路径读取失败");
        logStage("参数校验"); logErr("异常: 输出路径读取失败\n"); return (*env)->NewStringUTF(env, g_err);
    }
    char tagBuf[8];
    jstring_to_utf8(env, tag, tagBuf, sizeof(tagBuf));
    stageLog(logBuf, "[nativeConcat] 输出 = outBuf / tag = tagBuf");

    /* ---- 0. 输入文件校验：不存在/0 字节直接报错，避免 concat 打开坏文件时 native 崩溃 ---- */
    char origPaths[256][1024];
    for (int i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, inputPaths, i);
        char p[1024];
        if (!js || !jstring_to_utf8(env, js, p, sizeof(p))) {
            stageLog(logBuf, "[nativeConcat] 失败: 分集路径读取失败");
            logStage("校验分集文件"); logErr("异常: 分集 %d 路径读取失败\n", i + 1);
            return (*env)->NewStringUTF(env, g_err);
        }
        (*env)->DeleteLocalRef(env, js);
        struct stat st;
        if (stat(p, &st) != 0 || st.st_size <= 0) {
            stageLog(logBuf, "[nativeConcat] 失败: 分集文件缺失或为空");
            logStage("校验分集文件"); logErr("异常: 分集 %d 文件不存在或为空: %s\n", i + 1, p);
            return (*env)->NewStringUTF(env, g_err);
        }
        snprintf(origPaths[i], sizeof(origPaths[i]), "%s", p);
    }
    stageLog(logBuf, "[nativeConcat] 输入文件校验通过 (N 个均存在且非空)");

    /* ---- 0.45 音频参数探测：多数派 = 统一音轨目标（等价后端 probeFile/buildPlan
     * majorityKey(audioKey)；无音频轨的集不参与；参数不一致的集在规范化时转码） ---- */
    AudioInfo aTarget;
    {
        AudioInfo aInfos[256];
        for (int i = 0; i < n; i++) {
            char k[64] = {0};
            if (keys) {
                jstring ks = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
                if (ks) { jstring_to_utf8(env, ks, k, sizeof(k)); (*env)->DeleteLocalRef(env, ks); }
            }
            probe_audio_params(origPaths[i], k, &aInfos[i]);
        }
        majority_audio(aInfos, n, &aTarget);
        char wl[160];
        snprintf(wl, sizeof(wl), "[nativeConcat] 音频多数派目标: codec=%d prof=%d sr=%d ch=%d",
                 aTarget.codec, aTarget.profile, aTarget.sample_rate, aTarget.channels);
        stageLog(logBuf, wl);
    }

    /* ---- 0.46 视频编码探测：h264/hevc 混装时少数服从多数（等价后端 buildPlan 的 step.video：
     * 多数派 hevc → 少数派转 libx265；多数派 h264 → 少数派转 libx264。尺寸取多数派代表集） ---- */
    VideoTarget vt;
    memset(&vt, 0, sizeof(vt));
    {
        int vCodes[256], vWs[256], vHs[256];
        int hevcAll = 0, h264All = 0;
        for (int i = 0; i < n; i++) {
            char k[64] = {0};
            if (keys) {
                jstring ks = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
                if (ks) { jstring_to_utf8(env, ks, k, sizeof(k)); (*env)->DeleteLocalRef(env, ks); }
            }
            probe_video_codec(origPaths[i], k, &vCodes[i], &vWs[i], &vHs[i]);
            if (vCodes[i] == AV_CODEC_ID_HEVC) hevcAll++;
            else if (vCodes[i] == AV_CODEC_ID_H264) h264All++;
        }
        if (hevcAll > 0 && h264All > 0) {   /* 仅混装需要统一；全同编码时维持现状 -c copy */
            vt.active = 1;
            vt.targetCodec = (hevcAll >= h264All) ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
            vt.crf = (vt.targetCodec == AV_CODEC_ID_HEVC) ? 21 : 18;
            /* 多数派编码代表集的尺寸（转码目标） */
            for (int i = 0; i < n; i++) {
                if (vCodes[i] == vt.targetCodec && vWs[i] > 0 && vHs[i] > 0) {
                    vt.width = vWs[i]; vt.height = vHs[i];
                    break;
                }
            }
            if (vt.width <= 0 || vt.height <= 0) { vt.width = 0; vt.height = 0; vt.active = 0; }
        }
        char wl[192];
        if (vt.active)
            snprintf(wl, sizeof(wl), "[nativeConcat] 视频多数派目标: codec=%s crf=%d %dx%d（混装 %d×hevc + %d×h264）",
                     (vt.targetCodec == AV_CODEC_ID_HEVC) ? "hevc" : "h264", vt.crf, vt.width, vt.height,
                     hevcAll, h264All);
        else
            snprintf(wl, sizeof(wl), "[nativeConcat] 视频编码一致（hevc=%d h264=%d）无需视频转码",
                     hevcAll, h264All);
        stageLog(logBuf, wl);
    }

    /* ---- 0.5 阶段一：逐集规范化（-c copy 重封装，音频参数一致时纯 copy，不一致时统一音轨转码；
     * 等价后端：先逐集解密重封装/转码成干净分集，再 concat） ---- */
    char outDir[1024];
    {
        char* slash = strrchr(outBuf, '/');
        if (slash) { int dl = (int)(slash - outBuf); memcpy(outDir, outBuf, dl); outDir[dl] = 0; }
        else snprintf(outDir, sizeof(outDir), ".");
    }
    char segPaths[256][1100];
    int hevcN = 0, h264N = 0;   /* majority 编码统计（等价后端 majorityKey → tag 判定） */
    for (int i = 0; i < n; i++) {
        snprintf(segPaths[i], sizeof(segPaths[i]), "%s/seg_v7_%d.mp4", outDir, i + 1);
        char okPath[1150];
        snprintf(okPath, sizeof(okPath), "%s.ok", segPaths[i]);
        struct stat st;
        /* 复用条件：文件存在且非空 + .ok 完整标记存在（防止转码失败留下的半成品被误复用） */
        int reuse = (stat(segPaths[i], &st) == 0 && st.st_size > 0 && stat(okPath, &st) == 0);
        if (reuse) {
            char wl[256]; snprintf(wl, sizeof(wl), "[nativeConcat] 第%d集复用已规范化产物", i + 1);
            stageLog(logBuf, wl);
            continue;
        }
        /* 本集解密 key（可为空串：明文源） */
        char keyBuf[64] = {0};
        if (keys) {
            jstring ks = (jstring)(*env)->GetObjectArrayElement(env, keys, i);
            if (ks) { jstring_to_utf8(env, ks, keyBuf, sizeof(keyBuf)); (*env)->DeleteLocalRef(env, ks); }
        }
        char mimeV[32] = {0}, mimeA[32] = {0};
        int64_t nVP = 0, nAP = 0;
        int r = normalize_segment(origPaths[i], segPaths[i], i + 1, keyBuf, &aTarget, &vt, logBuf,
                                  mimeV, sizeof(mimeV), mimeA, sizeof(mimeA), &nVP, &nAP);
        if (r < 0) {
            char wl[256]; snprintf(wl, sizeof(wl), "[nativeConcat] 失败: 第%d集规范化失败 rc=%d", i + 1, r);
            stageLog(logBuf, wl);
            /* 失败：删除半成品 seg 与其 .ok（若有），下次合并必须重新规范化，绝不复用残缺分段 */
            remove(segPaths[i]);
            char okPath[1150]; snprintf(okPath, sizeof(okPath), "%s.ok", segPaths[i]);
            remove(okPath);
            return (*env)->NewStringUTF(env, g_err);
        }
        /* 规范化完整成功：写 .ok 完整标记（下次合并凭 seg+ok 复用；半成品无 .ok） */
        {
            char okPath[1150]; snprintf(okPath, sizeof(okPath), "%s.ok", segPaths[i]);
            FILE* okf = fopen(okPath, "w");
            if (okf) { fprintf(okf, "complete\n"); fclose(okf); }
        }
        if (strncmp(mimeV, "hevc", 4) == 0) hevcN++;
        else if (strncmp(mimeV, "h264", 4) == 0 || strncmp(mimeV, "avc", 3) == 0) h264N++;
        char wl[256];
        if (mimeA[0])
            snprintf(wl, sizeof(wl), "[nativeConcat] 第%d集规范化完成: 视频mime=%s 音频mime=%s 视频包=%lld 音频包=%lld",
                     i + 1, mimeV[0] ? mimeV : "?", mimeA, (long long)nVP, (long long)nAP);
        else
            snprintf(wl, sizeof(wl), "[nativeConcat] 第%d集规范化完成: 视频mime=%s 音频mime=(无音频轨道) 视频包=%lld 音频包=0",
                     i + 1, mimeV[0] ? mimeV : "?", (long long)nVP);
        stageLog(logBuf, wl);
    }
    /* tag 判定：优先 C 层 majority（探测不到时退回 Java 传入 tag） */
    if (hevcN || h264N) {
        snprintf(tagBuf, sizeof(tagBuf), "%s", (hevcN >= h264N) ? "hvc1" : "avc1");
    }
    stageLog(logBuf, "[nativeConcat] 全部 N 集规范化完成");

    /* ---- 1. 生成 ffconcat 列表（规范化产物 + 绝对路径 + safe=0；与后端 -safe 0 一致）---- */
    char listPath[1100];
    snprintf(listPath, sizeof(listPath), "%s.inputs_%ld.ffconcat", outBuf, (long)(av_gettime() / 1000000));
    FILE* lf = fopen(listPath, "w");
    if (!lf) {
        stageLog(logBuf, "[nativeConcat] 失败: 无法写入 ffconcat 列表");
        logStage("创建 ffconcat 列表"); logErr("异常: 无法写入 %s\n", listPath); return (*env)->NewStringUTF(env, g_err);
    }
    fprintf(lf, "ffconcat version 1.0\n");
    for (int i = 0; i < n; i++) {
        char* q = segPaths[i];
        /* 单引号转义：' -> '\'' */
        fprintf(lf, "file '");
        for (; *q; q++) { if (*q == '\'') fputs("'\\''", lf); else fputc(*q, lf); }
        fprintf(lf, "'\n");
    }
    fclose(lf);
    stageLog(logBuf, "[nativeConcat] ffconcat 列表已写入");

    /* ---- 2. 打开 concat 输入（-f concat -safe 0）---- */
    AVFormatContext* in = NULL;
    AVDictionary* opts = NULL;
    av_dict_set(&opts, "safe", "0", 0);
    av_dict_set(&opts, "auto_convert", "0", 0);   /* 禁 h264_mp4toannexb bsf：normalize 产物样本已是
                                                   * length-prefixed，concat 不再反向转换，最终 movenc 直接写标准 avc1 */
    AVInputFormat* fmt = av_find_input_format("concat");
    int rc = avformat_open_input(&in, listPath, fmt, &opts);
    av_dict_free(&opts);
    if (rc < 0) {
        char e[128] = {0};
        av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avformat_open_input(concat) rc<0");
        logStage("打开 concat 输入");
        logErr("异常: avformat_open_input(concat) 失败 rc=%d (%s)，列表: %s\n", rc, e, listPath);
        remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeConcat] avformat_open_input(concat) 成功");
    rc = avformat_find_stream_info(in, NULL);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avformat_find_stream_info rc<0");
        logStage("探测输入流信息"); logErr("异常: avformat_find_stream_info 失败 rc=%d (%s)\n", rc, e);
        avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    char si[128]; snprintf(si, sizeof(si), "[nativeConcat] find_stream_info 成功，流数=%d", (int)in->nb_streams);
    stageLog(logBuf, si);

    int vIdx = -1, aIdx = -1;
    pick_streams(in, &vIdx, &aIdx);
    if (vIdx < 0) {
        stageLog(logBuf, "[nativeConcat] 失败: 输入没有视频轨道");
        logStage("选择流"); logErr("异常: 输入没有视频轨道\n");
        avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    char vs[160]; snprintf(vs, sizeof(vs), "[nativeConcat] 选流: 视频#%d 音频#%d", vIdx, aIdx);
    stageLog(logBuf, vs);

    AVStream* vin = in->streams[vIdx];
    if (vin->codecpar->extradata_size <= 0 || !vin->codecpar->extradata) {
        stageLog(logBuf, "[nativeConcat] 失败: 视频流无 extradata(参数集)");
        logStage("选择流"); logErr("异常: 视频流无参数集(extradata)，无法 -c copy 合并\n");
        avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeConcat] 视频 extradata 正常（参数集存在）");

    /* 音频流也必须有 extradata（mov muxer 写 esds 会解引用，缺失即 native 崩） */
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        if (ain->codecpar->extradata_size <= 0 || !ain->codecpar->extradata) {
            stageLog(logBuf, "[nativeConcat] 失败: 音频流无 extradata(esds)");
            logStage("选择流"); logErr("异常: 音频流无参数集(extradata)，无法 -c copy 合并\n");
            avformat_close_input(&in); remove(listPath);
            return (*env)->NewStringUTF(env, g_err);
        }
        stageLog(logBuf, "[nativeConcat] 音频 extradata 正常（esds 存在）");
    }
    /* time_base 有效性：mov muxer 写头时依赖，0/0 会崩 */
    if (vin->time_base.num <= 0 || vin->time_base.den <= 0) {
        stageLog(logBuf, "[nativeConcat] 失败: 视频 time_base 无效");
        logStage("选择流"); logErr("异常: 视频 time_base 无效 %d/%d\n", vin->time_base.num, vin->time_base.den);
        avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        if (ain->time_base.num <= 0 || ain->time_base.den <= 0) {
            stageLog(logBuf, "[nativeConcat] 失败: 音频 time_base 无效");
            logStage("选择流"); logErr("异常: 音频 time_base 无效 %d/%d\n", ain->time_base.num, ain->time_base.den);
            avformat_close_input(&in); remove(listPath);
            return (*env)->NewStringUTF(env, g_err);
        }
    }

    /* ---- 3. 输出 mp4（timescale=多数派视频 time_base den，对齐后端 plan.timescale；
     *           -movflags +faststart -tag:v xxx；max_muxing_queue_size 4096 对齐后端）---- */
    int timescale = 90000;   /* 后端：tbScale>0 且 <=10000000 时取多数派 den */
    if (vin->time_base.den > 0 && vin->time_base.den <= 10000000) timescale = vin->time_base.den;
    AVFormatContext* oc = NULL;
    rc = avformat_alloc_output_context2(&oc, NULL, "mp4", outBuf);
    if (rc < 0 || !oc) {
        char e[128] = {0}; if (rc < 0) av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avformat_alloc_output_context2");
        logStage("创建输出上下文"); logErr("异常: avformat_alloc_output_context2 失败 rc=%d (%s)\n", rc, e);
        avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    char tsStr[24]; snprintf(tsStr, sizeof(tsStr), "%d", timescale);
    av_opt_set(oc->priv_data, "video_track_timescale", tsStr, 0);
    av_opt_set(oc->priv_data, "movflags", "+faststart", 0);
    av_opt_set_int(oc, "max_muxing_queue_size", 4096, 0);
    stageLog(logBuf, "[nativeConcat] 输出上下文创建成功，timescale=NNNN faststart");

    /* 关键：alloc_output_context2 不会初始化 pb，必须先 avio_open 打开输出文件，
     * 否则 write_header 里 mov muxer 解引用 NULL pb → SIGSEGV（此前闪退根因） */
    rc = avio_open(&oc->pb, outBuf, AVIO_FLAG_WRITE);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avio_open 输出文件");
        logStage("打开输出文件"); logErr("异常: avio_open 失败 rc=%d (%s): %s\n", rc, e, outBuf);
        cleanup_output(oc); avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeConcat] avio_open 输出文件成功");

    AVStream* vout = avformat_new_stream(oc, NULL);
    if (!vout) {
        stageLog(logBuf, "[nativeConcat] 失败: avformat_new_stream(video)");
        logStage("创建输出流(video)"); logErr("异常: avformat_new_stream 失败\n");
        cleanup_output(oc); avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    rc = avcodec_parameters_copy(vout->codecpar, vin->codecpar);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avcodec_parameters_copy(video)");
        logStage("复制视频参数"); logErr("异常: avcodec_parameters_copy(video) 失败 rc=%d (%s)\n", rc, e);
        cleanup_output(oc); avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    vout->codecpar->codec_tag = make_tag(tagBuf);   /* -tag:v hvc1/avc1（movenc 表仅收录 avc1/hvc1；avc3/hev1 被 validate_codec_tag 拒绝） */
    vout->time_base = vin->time_base;

    AVStream* aout = NULL;
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        aout = avformat_new_stream(oc, NULL);
        if (!aout) {
            stageLog(logBuf, "[nativeConcat] 失败: avformat_new_stream(audio)");
            logStage("创建输出流(audio)"); logErr("异常: avformat_new_stream 失败\n");
            cleanup_output(oc); avformat_close_input(&in); remove(listPath);
            return (*env)->NewStringUTF(env, g_err);
        }
        rc = avcodec_parameters_copy(aout->codecpar, ain->codecpar);
        if (rc < 0) {
            char e[128] = {0}; av_strerror(rc, e, sizeof(e));
            stageLog(logBuf, "[nativeConcat] 失败: avcodec_parameters_copy(audio)");
            logStage("复制音频参数"); logErr("异常: avcodec_parameters_copy(audio) 失败 rc=%d (%s)\n", rc, e);
            cleanup_output(oc); avformat_close_input(&in); remove(listPath);
            return (*env)->NewStringUTF(env, g_err);
        }
        aout->codecpar->codec_tag = 0;  /* 防污染 tag 校验（validate_codec_tag 拒绝非 mp4 表 tag → INVALIDDATA） */
        aout->time_base = ain->time_base;
    }
    stageLog(logBuf, "[nativeConcat] 输出流创建完成(video+audio)");

    AVDictionary* _mopts = NULL;
        av_dict_set(&_mopts, "movflags", "faststart+delay_moov", 0);
        rc = avformat_write_header(oc, &_mopts);
        av_dict_free(&_mopts);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: avformat_write_header");
        logStage("写文件头"); logErr("异常: avformat_write_header 失败 rc=%d (%s)\n", rc, e);
        cleanup_output(oc); avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeConcat] 写文件头成功");

    /* ---- 4. 拷贝包（-c copy，等价后端 concat 阶段）---- */
    AVPacket* pkt = av_packet_alloc();
    if (!pkt) {
        stageLog(logBuf, "[nativeConcat] 失败: av_packet_alloc");
        logStage("拷贝音视频包"); logErr("异常: av_packet_alloc 失败\n");
        av_write_trailer(oc); cleanup_output(oc); avformat_close_input(&in); remove(listPath);
        return (*env)->NewStringUTF(env, g_err);
    }
    long written = 0;
    int readErr = 0;
    int gapLogN = 0;
    int64_t lastDtsV = AV_NOPTS_VALUE, lastDtsA = AV_NOPTS_VALUE;   /* 逐流 last dts（rescale 后单位） */
    while ((rc = av_read_frame(in, pkt)) >= 0) {
        int si = pkt->stream_index;
        if (si != vIdx && si != aIdx) { av_packet_unref(pkt); continue; }
        if (pkt->size <= 0) { av_packet_unref(pkt); continue; }   /* 跳过 concat 段边界空包 */
        AVStream* inSt = in->streams[si];
        AVStream* outSt = (si == vIdx) ? vout : aout;
        av_packet_rescale_ts(pkt, inSt->time_base, outSt->time_base);
        pkt->pos = -1;

        /* 等价后端 ffmpeg CLI concat 对 Non-monotonous DTS 的处理：CLI 的 mov muxer 默认
         * strict_std_compliance=NORMAL，遇 dts 重复/回退打印 warning 并 +1 修正（不报错）。
         * concat demuxer 在段边界会把下集首帧对齐到上集末帧（dts 重复），做同款 +1 修正，
         * 仅边界单帧微移（1 刻度 ≈ 22µs），与后端产物行为一致。 */
        int64_t* lastDts = (si == vIdx) ? &lastDtsV : &lastDtsA;
        if (pkt->dts == AV_NOPTS_VALUE) {
            pkt->dts = (*lastDts == AV_NOPTS_VALUE) ? 0 : *lastDts + 1;
        }
        if (pkt->pts == AV_NOPTS_VALUE) pkt->pts = pkt->dts;
        if (*lastDts != AV_NOPTS_VALUE && pkt->dts <= *lastDts) {
            pkt->dts = *lastDts + 1;
            if (pkt->pts < pkt->dts) pkt->pts = pkt->dts;
        }
        *lastDts = pkt->dts;

        /* gap 检测：某流 dts 跳跃远超平均帧间隔（阈值≈2s 刻度）时记录，用于定位 seek 丢声/乱码段 */
        if (gapLogN < 20) {
            int64_t jump = 0;
            int64_t refPrev = AV_NOPTS_VALUE;
            if (si == vIdx && lastDtsV != AV_NOPTS_VALUE) refPrev = lastDtsV;
            if (si == aIdx && lastDtsA != AV_NOPTS_VALUE) refPrev = lastDtsA;
            if (refPrev != AV_NOPTS_VALUE && pkt->dts != AV_NOPTS_VALUE && pkt->dts > refPrev) {
                jump = pkt->dts - refPrev;
                if (jump > 100000) {
                    char gl[200];
                    snprintf(gl, sizeof(gl), "[gap] %s dts 跳跃: 位置≈%0.1fs 前=%lld 后=%lld Δ=%lld",
                             (si == vIdx) ? "video" : "audio",
                             (double)pkt->dts * inSt->time_base.num / inSt->time_base.den,
                             (long long)refPrev, (long long)pkt->dts, (long long)jump);
                    stageLog(logBuf, gl);
                    gapLogN++;
                }
            }
        }

        rc = av_interleaved_write_frame(oc, pkt);
        int failRc = rc, failSize = pkt->size;
        long long failPts = pkt->pts, failDts = pkt->dts;
        av_packet_unref(pkt);
        if (failRc < 0) {
            char e[128] = {0}; av_strerror(failRc, e, sizeof(e));
            char wl[256]; snprintf(wl, sizeof(wl), "[nativeConcat] 失败: av_interleaved_write_frame rc<0 (包数=%ld)", written);
            stageLog(logBuf, wl);
            logStage("拷贝音视频包");
            logErr("异常: av_interleaved_write_frame 失败 rc=%d (%s)，已写 %ld 包；包信息: stream=%d(%s) pts=%lld dts=%lld size=%d\n",
                   failRc, e, written, si, (si == vIdx) ? "video" : "audio", failPts, failDts, failSize);
            av_write_trailer(oc); av_packet_free(&pkt);
            cleanup_output(oc); avformat_close_input(&in); remove(listPath);
            return (*env)->NewStringUTF(env, g_err);
        }
        written++;
        if (written % 5000 == 0) {
            char wl[128]; snprintf(wl, sizeof(wl), "[nativeConcat] 已拷贝 %ld 包", written);
            stageLog(logBuf, wl);
        }
    }
    readErr = rc;
    char done[128]; snprintf(done, sizeof(done), "[nativeConcat] 拷贝完成，共 %ld 包 (av_read_frame rc=%d)", written, readErr);
    stageLog(logBuf, done);
    rc = av_write_trailer(oc);
    stageLog(logBuf, "[nativeConcat] av_write_trailer 完成");
    av_packet_free(&pkt);
    cleanup_output(oc);   /* 关闭 pb（faststart moov 已写回）+ free context */
    avformat_close_input(&in);
    remove(listPath);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        stageLog(logBuf, "[nativeConcat] 失败: av_write_trailer rc<0");
        logStage("写文件尾"); logErr("异常: av_write_trailer 失败 rc=%d (%s)\n", rc, e);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeConcat] 合并成功");
    /* 清理规范化中间产物（保留原始分集文件，方便调试复用） */
    for (int i = 0; i < n; i++) {
        remove(segPaths[i]);
        char okPath[1150]; snprintf(okPath, sizeof(okPath), "%s.ok", segPaths[i]);
        remove(okPath);
    }
    return NULL;   /* 成功 */
}

/* ---- 本地 HLS(m3u8) → MP4 重封装 ----
 * 等价 CLI：ffmpeg -allowed_extensions ALL -i playlist.m3u8 -c copy -f mp4 -y out
 * playlist 与分片/KEY 同目录（本地相对路径），hls demuxer 原生处理 AES-128 解密。
 * 用于黄果源手机本地下载：APK 拉 m3u8/分片到本地后直接转成可播 mp4，不依赖后端转码。 */
static int hls_remux(const char* playlistPath, const char* outPath, char* logBuf) {
    AVFormatContext* in = NULL;
    AVDictionary* opts = NULL;
    av_dict_set(&opts, "allowed_extensions", "ALL", 0);
    int rc = avformat_open_input(&in, playlistPath, NULL, &opts);
    av_dict_free(&opts);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "打开 m3u8 输入"); logErrBoth(logBuf, "异常: avformat_open_input(m3u8) 失败 rc=%d (%s)\n", rc, e);
        return rc;
    }
    rc = avformat_find_stream_info(in, NULL);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "探测 m3u8 流信息"); logErrBoth(logBuf, "异常: find_stream_info 失败 rc=%d (%s)\n", rc, e);
        avformat_close_input(&in); return rc;
    }
    int vIdx = -1, aIdx = -1;
    pick_streams(in, &vIdx, &aIdx);
    if (vIdx < 0) {
        logStageB(logBuf, "选择流"); logErrBoth(logBuf, "异常: m3u8 输入没有视频轨道\n");
        avformat_close_input(&in); return AVERROR(EINVAL);
    }
    AVStream* vin = in->streams[vIdx];
    if (vin->codecpar->extradata_size <= 0 || !vin->codecpar->extradata) {
        logStageB(logBuf, "选择流"); logErrBoth(logBuf, "异常: 视频流无参数集(extradata)，无法 -c copy 封装\n");
        avformat_close_input(&in); return AVERROR(EINVAL);
    }

    int timescale = 90000;
    if (vin->time_base.den > 0 && vin->time_base.den <= 10000000) timescale = vin->time_base.den;
    AVFormatContext* oc = NULL;
    rc = avformat_alloc_output_context2(&oc, NULL, "mp4", outPath);
    if (rc < 0 || !oc) {
        char e[128] = {0}; if (rc < 0) av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "创建输出上下文"); logErrBoth(logBuf, "异常: avformat_alloc_output_context2 失败 rc=%d (%s)\n", rc, e);
        avformat_close_input(&in); return rc ? rc : AVERROR(ENOMEM);
    }
    char tsStr[24]; snprintf(tsStr, sizeof(tsStr), "%d", timescale);
    av_opt_set(oc->priv_data, "video_track_timescale", tsStr, 0);
    av_opt_set(oc->priv_data, "movflags", "+faststart", 0);
    av_opt_set_int(oc, "max_muxing_queue_size", 4096, 0);
    rc = avio_open(&oc->pb, outPath, AVIO_FLAG_WRITE);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "打开输出文件"); logErrBoth(logBuf, "异常: avio_open 失败 rc=%d (%s)\n", rc, e);
        cleanup_output(oc); avformat_close_input(&in); return rc;
    }

    AVStream* vout = avformat_new_stream(oc, NULL);
    if (!vout) {
        logStageB(logBuf, "创建输出流(video)"); logErrBoth(logBuf, "异常: avformat_new_stream(video) 失败\n");
        cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM);
    }
    avcodec_parameters_copy(vout->codecpar, vin->codecpar);
    /* in-band 参数集 tag（等价 concat 的 hvc1/avc1），保持 APK 播放/后续合并一致 */
    vout->codecpar->codec_tag = make_tag(vin->codecpar->codec_id == AV_CODEC_ID_HEVC ? "hvc1" : "avc1");
    vout->time_base = vin->time_base;

    AVStream* aout = NULL;
    if (aIdx >= 0) {
        AVStream* ain = in->streams[aIdx];
        aout = avformat_new_stream(oc, NULL);
        if (!aout) {
            logStageB(logBuf, "创建输出流(audio)"); logErrBoth(logBuf, "异常: avformat_new_stream(audio) 失败\n");
            cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM);
        }
        avcodec_parameters_copy(aout->codecpar, ain->codecpar);
        aout->codecpar->codec_tag = 0;  /* TS demuxer 会把 stream_type(0x0F) 塞进 codec_tag，mp4 muxer validate_codec_tag 拒绝 → INVALIDDATA；清 0 由 movenc 自动分配 mp4a */
        aout->time_base = ain->time_base;
    }

    AVDictionary* _mopts = NULL;
        av_dict_set(&_mopts, "movflags", "faststart+delay_moov", 0);
        rc = avformat_write_header(oc, &_mopts);
        av_dict_free(&_mopts);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "写文件头"); logErrBoth(logBuf, "异常: avformat_write_header 失败 rc=%d (%s)\n", rc, e);
        cleanup_output(oc); avformat_close_input(&in); return rc;
    }

    AVPacket* pkt = av_packet_alloc();
    if (!pkt) {
        logStageB(logBuf, "拷贝音视频包"); logErrBoth(logBuf, "异常: av_packet_alloc 失败\n");
        av_write_trailer(oc); cleanup_output(oc); avformat_close_input(&in); return AVERROR(ENOMEM);
    }
    long written = 0;
    int64_t lastDtsV = AV_NOPTS_VALUE, lastDtsA = AV_NOPTS_VALUE;
    while ((rc = av_read_frame(in, pkt)) >= 0) {
        int si = pkt->stream_index;
        if (si != vIdx && si != aIdx) { av_packet_unref(pkt); continue; }
        if (pkt->size <= 0) { av_packet_unref(pkt); continue; }
        AVStream* inSt = in->streams[si];
        AVStream* outSt = (si == vIdx) ? vout : aout;
        av_packet_rescale_ts(pkt, inSt->time_base, outSt->time_base);
        pkt->pos = -1;
        int64_t* lastDts = (si == vIdx) ? &lastDtsV : &lastDtsA;
        if (pkt->dts == AV_NOPTS_VALUE) pkt->dts = (*lastDts == AV_NOPTS_VALUE) ? 0 : *lastDts + 1;
        if (pkt->pts == AV_NOPTS_VALUE) pkt->pts = pkt->dts;
        if (*lastDts != AV_NOPTS_VALUE && pkt->dts <= *lastDts) { pkt->dts = *lastDts + 1; if (pkt->pts < pkt->dts) pkt->pts = pkt->dts; }
        *lastDts = pkt->dts;
        int wr = av_interleaved_write_frame(oc, pkt);
        int failRc = wr; long long failPts = pkt->pts, failDts = pkt->dts;
        av_packet_unref(pkt);
        if (failRc < 0) {
            char e[128] = {0}; av_strerror(failRc, e, sizeof(e));
            logStageB(logBuf, "拷贝音视频包");
            logErrBoth(logBuf, "异常: av_interleaved_write_frame 失败 rc=%d (%s)，已写 %ld 包；包信息: stream=%d pts=%lld dts=%lld size=%d\n",
                       failRc, e, written, si, failPts, failDts, pkt->size);
            av_write_trailer(oc); av_packet_free(&pkt);
            cleanup_output(oc); avformat_close_input(&in); return failRc;
        }
        written++;
    }
    rc = av_write_trailer(oc);
    av_packet_free(&pkt);
    cleanup_output(oc);
    avformat_close_input(&in);
    if (rc < 0) {
        char e[128] = {0}; av_strerror(rc, e, sizeof(e));
        logStageB(logBuf, "写文件尾"); logErrBoth(logBuf, "异常: av_write_trailer 失败 rc=%d (%s)\n", rc, e);
        return rc;
    }
    stageLog(logBuf, "[hls_remux] m3u8 转 mp4 成功");
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_veyra_gybeyond_FFmpegConcat_nativeM3u8ToMp4(JNIEnv* env, jclass cls,
                                                     jstring playlistPath,
                                                     jstring outputPath,
                                                     jstring logPath)
{
    g_err[0] = 0; g_errLen = 0;
    char logBuf[1024];
    if (!jstring_to_utf8(env, logPath, logBuf, sizeof(logBuf))) logBuf[0] = 0;
    install_crash_handlers(logBuf);
    stageLog(logBuf, "[nativeM3u8ToMp4] 进入 native m3u8 remux");

    char inBuf[1024];
    if (!jstring_to_utf8(env, playlistPath, inBuf, sizeof(inBuf))) {
        logStage("参数校验"); logErr("异常: playlist 路径读取失败\n"); return (*env)->NewStringUTF(env, g_err);
    }
    char outBuf[1024];
    if (!jstring_to_utf8(env, outputPath, outBuf, sizeof(outBuf))) {
        logStage("参数校验"); logErr("异常: 输出路径读取失败\n"); return (*env)->NewStringUTF(env, g_err);
    }
    struct stat st;
    if (stat(inBuf, &st) != 0 || st.st_size <= 0) {
        logStage("校验输入"); logErr("异常: playlist 文件不存在或为空: %s\n", inBuf);
        return (*env)->NewStringUTF(env, g_err);
    }
    stageLog(logBuf, "[nativeM3u8ToMp4] 输入校验通过");
    int rc = hls_remux(inBuf, outBuf, logBuf);
    if (rc < 0) {
        stageLog(logBuf, "[nativeM3u8ToMp4] 失败");
        return (*env)->NewStringUTF(env, g_err);
    }
    if (!g_err[0]) g_err[0] = 0;
    return NULL;   /* 成功 */
}
