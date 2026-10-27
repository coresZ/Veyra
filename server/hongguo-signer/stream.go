package main

import (
	"encoding/hex"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"math/bits"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Sample represents an encrypted range within the MP4
type Sample struct {
	Start        int64
	End          int64
	IV           [16]byte
	EncryptedOff int64
}

type preparedVideo struct {
	header  []byte
	samples []Sample
	total   int64
}

var streamCache = struct {
	sync.Mutex
	items map[string]*cachedStream
}{items: make(map[string]*cachedStream)}

type cachedStream struct {
	data     []byte // decrypted MP4
	prepared *preparedVideo
	expiry   time.Time
}

func init() {
	http.HandleFunc("/stream", handleStream)
	go func() {
		for range time.Tick(5 * time.Minute) {
			streamCache.Lock()
			for k, v := range streamCache.items {
				if time.Now().After(v.expiry) {
					delete(streamCache.items, k)
				}
			}
			streamCache.Unlock()
		}
	}()
}

func handleStream(w http.ResponseWriter, r *http.Request) {
	vid := r.URL.Query().Get("vid")
	if vid == "" {
		http.Error(w, "missing vid", 400)
		return
	}

	streamCache.Lock()
	if cs, ok := streamCache.items[vid]; ok && time.Now().Before(cs.expiry) {
		streamCache.Unlock()
		serveDecrypted(w, r, cs.data)
		return
	}
	streamCache.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	result, err := resolveVideo(ctx, vid)
	if err != nil {
		http.Error(w, "resolve: "+err.Error(), 502)
		return
	}

	// video_model 可能是 JSON 字符串 / base64(JSON)（guoapp 对齐：先 JSON 后 base64）
	vmRaw := result["data"].(map[string]any)["video_model"]
	vmStr := ""
	if s, ok := vmRaw.(string); ok {
		vmStr = strings.TrimSpace(s)
		if !strings.HasPrefix(vmStr, "{") {
			if dec, derr := base64.StdEncoding.DecodeString(vmStr); derr == nil {
				vmStr = string(dec)
			}
		}
	} else if b, ok := vmRaw.([]byte); ok {
		vmStr = string(b)
	}
	var vm struct {
		VideoList []struct {
			MainURL    string   `json:"main_url"`
			BackupURL  string   `json:"backup_url"`
			BackupURLs []string `json:"backup_urls"`
			GearDesKey string   `json:"gear_des_key"`
			EncryptInfo struct {
				Encrypt bool   `json:"encrypt"`
				SpadeA  string `json:"spade_a"`
				Method  string `json:"encryption_method"`
			} `json:"encrypt_info"`
			VideoMeta struct {
				Definition string `json:"definition"`
				CodecType  string `json:"codec_type"`
				VWidth     int64  `json:"vwidth"`
				VHeight    int64  `json:"vheight"`
			} `json:"video_meta"`
		} `json:"video_list"`
	}
	if err := json.Unmarshal([]byte(vmStr), &vm); err != nil {
		http.Error(w, "video_model parse: "+err.Error(), 502)
		return
	}
	if len(vm.VideoList) == 0 {
		http.Error(w, "empty video_list", 502)
		return
	}

	// 选最高清晰度：height*10（h264/avc1 +1），跳过 bytevc2（含 gear_des_key）
	best := vm.VideoList[0]
	bestScore := scoreVariant(best)
	for _, v := range vm.VideoList {
		if s := scoreVariant(v); s > bestScore {
			best = v
			bestScore = s
		}
	}

	cdnURL := best.MainURL
	if !strings.HasPrefix(cdnURL, "http") {
		if dec, derr := base64.StdEncoding.DecodeString(cdnURL); derr == nil && strings.HasPrefix(string(dec), "http") {
			cdnURL = string(dec)
		}
	}
	if cdnURL == "" || !strings.HasPrefix(cdnURL, "http") {
		cdnURL = best.BackupURL
	}
	if !strings.HasPrefix(cdnURL, "http") {
		if dec, derr := base64.StdEncoding.DecodeString(cdnURL); derr == nil && strings.HasPrefix(string(dec), "http") {
			cdnURL = string(dec)
		}
	}
	for _, u := range best.BackupURLs {
		if cdnURL != "" && strings.HasPrefix(cdnURL, "http") {
			break
		}
		if strings.HasPrefix(u, "http") {
			cdnURL = u
		} else if dec, derr := base64.StdEncoding.DecodeString(u); derr == nil && strings.HasPrefix(string(dec), "http") {
			cdnURL = string(dec)
		}
	}
	if cdnURL == "" || !strings.HasPrefix(cdnURL, "http") {
			http.Error(w, "无可用播放地址", 502)
		return
	}

	// Derive AES key from spade_a
	var key []byte
	if best.EncryptInfo.Encrypt && best.EncryptInfo.SpadeA != "" {
		key = deriveKey(best.EncryptInfo.SpadeA)
	} else if strings.EqualFold(best.EncryptInfo.Method, "cenc-aes-ctr") && best.EncryptInfo.SpadeA != "" {
		key = deriveKey(best.EncryptInfo.SpadeA)
	}

	// Download full encrypted MP4
	fmt.Printf("[stream] downloading vid=%s quality=%s\n", vid, best.VideoMeta.Definition)
	encData, err := downloadFull(ctx, cdnURL)
	if err != nil {
		http.Error(w, "download: "+err.Error(), 502)
		return
	}
	fmt.Printf("[stream] downloaded %d bytes, decrypting...\n", len(encData))

	// Parse and decrypt
	pv, err := prepareDecrypt(encData, key)
	if err != nil {
		http.Error(w, "decrypt: "+err.Error(), 502)
		return
	}

	fmt.Printf("[stream] decrypted OK, serving %d bytes\n", len(pv.header)+len(encData)-len(pv.header))

	// Cache it
	cs := &cachedStream{
		data:     encData,
		prepared: pv,
		expiry:   time.Now().Add(2 * time.Hour),
	}
	streamCache.Lock()
	streamCache.items[vid] = cs
	streamCache.Unlock()

	serveDecrypted(w, r, encData)
}

func serveDecrypted(w http.ResponseWriter, r *http.Request, data []byte) {
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Accept-Ranges", "bytes")

	rangeHeader := r.Header.Get("Range")
	if rangeHeader == "" {
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.Write(data)
		return
	}

	parts := strings.SplitN(strings.TrimPrefix(rangeHeader, "bytes="), "-", 2)
	start, _ := strconv.ParseInt(parts[0], 10, 64)
	end := int64(len(data) - 1)
	if len(parts) > 1 && parts[1] != "" {
		end, _ = strconv.ParseInt(parts[1], 10, 64)
	}
	if end >= int64(len(data)) {
		end = int64(len(data)) - 1
	}
	if start > end || start < 0 {
		w.WriteHeader(416)
		return
	}

	w.WriteHeader(206)
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
	w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	w.Write(data[start : end+1])
}

func downloadFull(ctx context.Context, url string) ([]byte, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	req.Header.Set("User-Agent", hongguoAppUserAgent)
	req.Header.Set("Referer", "https://novel.snssdk.com/")
	req.Header.Set("Accept-Encoding", "identity")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 206 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 50<<20))
	return data, err
}

func scoreVariant(v struct {
	MainURL    string   `json:"main_url"`
	BackupURL  string   `json:"backup_url"`
	BackupURLs []string `json:"backup_urls"`
	GearDesKey string   `json:"gear_des_key"`
	EncryptInfo struct {
		Encrypt bool   `json:"encrypt"`
		SpadeA  string `json:"spade_a"`
		Method  string `json:"encryption_method"`
	} `json:"encrypt_info"`
	VideoMeta struct {
		Definition string `json:"definition"`
		CodecType  string `json:"codec_type"`
		VWidth     int64  `json:"vwidth"`
		VHeight    int64  `json:"vheight"`
	} `json:"video_meta"`
}) int {
	codec := strings.ToLower(v.VideoMeta.CodecType)
	if strings.Contains(codec, "bytevc2") || strings.Contains(strings.ToLower(v.GearDesKey), "bytevc2") {
		return -1
	}
	h := v.VideoMeta.VHeight
	w := v.VideoMeta.VWidth
	if h <= 0 {
		h = w
	}
	score := int(h) * 10
	if codec == "h264" || codec == "avc1" {
		score++
	}
	return score
}

func deriveKey(spadeA string) []byte {
	if k, err := deriveKeyNew(spadeA); err == nil {
		return k
	}
	return deriveKeyLegacy(spadeA)
}

func deriveKeyNew(spadeA string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(spadeA)
	if err != nil {
		return nil, err
	}
	if len(raw) < 3 {
		return nil, fmt.Errorf("spade_a 过短")
	}
	tagLength := int(raw[0]^raw[1]^raw[2]) - 48
	contentLength := len(raw) - tagLength - 1
	if tagLength < 1 || contentLength < 33 || contentLength >= len(raw) {
		return nil, fmt.Errorf("spade_a 结构不匹配")
	}
	seed := raw[len(raw)-tagLength-2] ^ raw[len(raw)-tagLength-1]
	tag := make([]byte, tagLength)
	for i := 0; i < tagLength; i++ {
		tag[i] = raw[len(raw)-tagLength+i] ^ seed
	}
	if string(tag) == "app_v2" || string(tag) == "web_v2" {
		return nil, fmt.Errorf("不支持的 spade_a 新格式")
	}
	decoded := make([]byte, contentLength)
	prevEven, prevOdd := 250, 85
	for idx := 0; idx < contentLength; idx++ {
		cur := raw[1+idx]
		var prev byte
		if idx&1 == 0 {
			prev = byte(prevEven)
			prevEven = int(cur)
		} else {
			prev = byte(prevOdd)
			prevOdd = int(cur)
		}
		decoded[idx] = byte((int(prev) ^ int(cur)) - 21 - bits.OnesCount(uint(idx)))
	}
	var padding int
	c0 := decoded[0]
	if c0 >= '0' && c0 <= '9' {
		padding = int(c0 - '0')
	} else if c0 >= 'a' && c0 <= 'z' {
		padding = int(c0-'a') + 10
	} else {
		return nil, fmt.Errorf("spade_a padding 非法")
	}
	if contentLength-padding-1 != 32 {
		return nil, fmt.Errorf("spade_a key 长度异常")
	}
	key, err := hex.DecodeString(string(decoded[1:33]))
	if err != nil || len(key) != 16 {
		return nil, fmt.Errorf("spade_a key 解码异常")
	}
	return key, nil
}

func deriveKeyLegacy(spadeA string) []byte {
	raw, err := base64.StdEncoding.DecodeString(spadeA)
	if err != nil {
		raw, _ = base64.URLEncoding.DecodeString(spadeA)
	}
	if len(raw) < 3 {
		return nil
	}
	xor3 := raw[0] ^ raw[1] ^ raw[2]
	length := len(raw) - int(xor3) + 47
	if length > len(raw)-1 {
		length = len(raw) - 1
	}
	if length < 33 {
		return nil
	}
	work := make([]byte, length)
	copy(work, raw[1:1+length])
	a, b := byte(85), byte(246)
	for i := 0; i < len(work); i++ {
		old := work[i]
		var prev byte
		if i&1 != 0 {
			prev = a
			a = old
		} else {
			prev = b
			b = old
		}
		work[i] = ((prev ^ old) - 21 - byte(bits.OnesCount(uint(i))))
	}
	hexStr := string(work[1:33])
	key, _ := hexDecode(hexStr)
	return key
}

func hexDecode(s string) ([]byte, error) {
	if len(s)%2 != 0 {
		return nil, fmt.Errorf("odd hex length")
	}
	out := make([]byte, len(s)/2)
	for i := 0; i < len(out); i++ {
		hi := fromHexChar(s[i*2])
		lo := fromHexChar(s[i*2+1])
		out[i] = (hi << 4) | lo
	}
	return out, nil
}

func fromHexChar(c byte) byte {
	switch {
	case c >= '0' && c <= '9':
		return c - '0'
	case c >= 'a' && c <= 'f':
		return c - 'a' + 10
	case c >= 'A' && c <= 'F':
		return c - 'A' + 10
	}
	return 0
}

// MP4 box parsing
type mp4Box struct {
	start int
	end   int
	body  int
	btype string
	size  int
}

func readBox(data []byte, at int) (*mp4Box, error) {
	if at+8 > len(data) {
		return nil, fmt.Errorf("box too small")
	}
	size := int(binary.BigEndian.Uint32(data[at : at+4]))
	bt := string(data[at+4 : at+8])
	hs := 8
	if size == 1 {
		if at+16 > len(data) {
			return nil, fmt.Errorf("large box too small")
		}
		size = int(binary.BigEndian.Uint64(data[at+8 : at+16]))
		hs = 16
	}
	if size < hs || at+size > len(data) {
		return nil, fmt.Errorf("box size out of range")
	}
	return &mp4Box{start: at, end: at + size, btype: bt, size: size}, nil
}

func findBox(data []byte, btype string, start, end int) (*mp4Box, error) {
	at := start
	for at < end-8 {
		b, err := readBox(data, at)
		if err != nil {
			break
		}
		if b.btype == btype {
			return b, nil
		}
		at = b.end
	}
	return nil, fmt.Errorf("box %s not found", btype)
}

func prepareDecrypt(data []byte, key []byte) (*preparedVideo, error) {
	if len(data) < 16 || string(data[4:8]) != "ftyp" {
		return nil, fmt.Errorf("not MP4")
	}
	ftypSize := int(binary.BigEndian.Uint32(data[0:4]))
	moov, err := readBox(data, ftypSize)
	if err != nil {
		return nil, err
	}
	if moov.btype != "moov" {
		return nil, fmt.Errorf("no moov box")
	}

	var samples []Sample
	at := moov.body
	for at < moov.end-8 {
		trak, err := readBox(data, at)
		if err != nil {
			break
		}
		at = trak.end
		if trak.btype != "trak" {
			continue
		}

		mdia, err := findBox(data, "mdia", trak.body, trak.end)
		if err != nil {
			continue
		}
		minf, err := findBox(data, "minf", mdia.body, mdia.end)
		if err != nil {
			continue
		}
		stbl, err := findBox(data, "stbl", minf.body, minf.end)
		if err != nil {
			continue
		}

		saiz, err := findBox(data, "saiz", stbl.body, stbl.end)
		if err != nil {
			continue
		}
		saio, err := findBox(data, "saio", stbl.body, stbl.end)
		if err != nil {
			return nil, fmt.Errorf("no saio")
		}
		stsz, err := findBox(data, "stsz", stbl.body, stbl.end)
		if err != nil {
			return nil, fmt.Errorf("no stsz")
		}
		stsc, err := findBox(data, "stsc", stbl.body, stbl.end)
		if err != nil {
			return nil, fmt.Errorf("no stsc")
		}
		stco, err := findBox(data, "stco", stbl.body, stbl.end)
		if err != nil {
			stco, err = findBox(data, "co64", stbl.body, stbl.end)
			if err != nil {
				return nil, fmt.Errorf("no stco/co64")
			}
		}

		sampleSizeDefault := binary.BigEndian.Uint32(data[stsz.body+4 : stsz.body+8])
		sampleCount := int(binary.BigEndian.Uint32(data[stsz.body+8 : stsz.body+12]))
		sizes := make([]int64, sampleCount)
		if sampleSizeDefault > 0 {
			for i := range sizes {
				sizes[i] = int64(sampleSizeDefault)
			}
		} else {
			for i := 0; i < sampleCount; i++ {
				sizes[i] = int64(binary.BigEndian.Uint32(data[stsz.body+12+i*4:]))
			}
		}

		stscCount := int(binary.BigEndian.Uint32(data[stsc.body+4 : stsc.body+8]))
		type stscEntry struct {
			firstChunk uint32
			samplesPer uint32
		}
		entries := make([]stscEntry, stscCount)
		for i := 0; i < stscCount; i++ {
			entries[i].firstChunk = binary.BigEndian.Uint32(data[stsc.body+8+i*12:])
			entries[i].samplesPer = binary.BigEndian.Uint32(data[stsc.body+12+i*12:])
		}

		stcoCount := int(binary.BigEndian.Uint32(data[stco.body+4 : stco.body+8]))
		offWidth := 4
		if stco.btype == "co64" {
			offWidth = 8
		}
		offsets := make([]int64, stcoCount)
		for i := 0; i < stcoCount; i++ {
			off := stco.body + 8 + i*offWidth
			if offWidth == 8 {
				offsets[i] = int64(binary.BigEndian.Uint64(data[off:]))
			} else {
				offsets[i] = int64(binary.BigEndian.Uint32(data[off:]))
			}
		}

		saizFlags := binary.BigEndian.Uint32(data[saiz.body:])
		saizBase := saiz.body + 4
		if saizFlags&1 != 0 {
			saizBase += 8
		}
		auxDefault := data[saizBase]
		auxCount := int(binary.BigEndian.Uint32(data[saizBase+1 : saizBase+5]))
		auxSizes := make([]int, auxCount)
		if auxDefault > 0 {
			for i := range auxSizes {
				auxSizes[i] = int(auxDefault)
			}
		} else {
			for i := 0; i < auxCount; i++ {
				auxSizes[i] = int(data[saizBase+5+i])
			}
		}

		saioFlags := binary.BigEndian.Uint32(data[saio.body:])
		saioBase := saio.body + 4
		if saioFlags&1 != 0 {
			saioBase += 8
		}
		iofWidth := 4
		if data[saioBase] == 1 {
			iofWidth = 8
		}
		var auxOffset int64
		if iofWidth == 8 {
			auxOffset = int64(binary.BigEndian.Uint64(data[saioBase+4:]))
		} else {
			auxOffset = int64(binary.BigEndian.Uint32(data[saioBase+4:]))
		}

		sampleIdx := 0
		entryIdx := 0
		for chunkIdx := 0; chunkIdx < len(offsets); chunkIdx++ {
			for entryIdx+1 < len(entries) && entries[entryIdx+1].firstChunk <= uint32(chunkIdx+1) {
				entryIdx++
			}
			count := entries[entryIdx].samplesPer
			pos := offsets[chunkIdx]
			for s := 0; s < int(count); s++ {
				length := sizes[sampleIdx]
				asz := auxSizes[sampleIdx]
				if asz >= 8 {
					var iv [16]byte
					copy(iv[:8], data[auxOffset:auxOffset+8])
					if asz == 8 {
						samples = append(samples, Sample{Start: pos, End: pos + length, IV: iv, EncryptedOff: 0})
					} else {
						subs := int(binary.BigEndian.Uint16(data[auxOffset+8 : auxOffset+10]))
						off2 := pos
						enc := int64(0)
						for sub := 0; sub < subs; sub++ {
							at2 := auxOffset + 10 + int64(sub)*6
							clear := int(binary.BigEndian.Uint16(data[at2:]))
							sz := int64(binary.BigEndian.Uint32(data[at2+2:]))
							off2 += int64(clear)
							if sz > 0 {
								samples = append(samples, Sample{Start: off2, End: off2 + sz, IV: iv, EncryptedOff: enc})
							}
							enc += sz
							off2 += sz
						}
					}
				}
				pos += length
				auxOffset += int64(asz)
				sampleIdx++
			}
		}

		at2 := stbl.body
		for at2+8 <= stbl.end {
			sinf, err := findBox(data, "sinf", at2, stbl.end)
			if err != nil {
				break
			}
			frma, err := findBox(data, "frma", sinf.body, sinf.end)
			if err == nil && frma.body+4 <= frma.end {
				original := data[frma.body : frma.body+4]
				entryAt := sinf.start - 1
				for entryAt >= stbl.body {
					bt := string(data[entryAt : entryAt+4])
					if bt == "encv" || bt == "enca" {
						copy(data[entryAt:entryAt+4], original)
						break
					}
					entryAt--
				}
			}
			copy(data[sinf.start+4:sinf.start+8], []byte("free"))
			for i := sinf.body; i < sinf.end; i++ {
				data[i] = 0
			}
			at2 = sinf.end
		}
	}

	if len(samples) == 0 {
		return &preparedVideo{header: data, samples: samples, total: int64(len(data))}, nil
	}

	if key != nil && len(key) == 16 {
		block, err := aes.NewCipher(key)
		if err != nil {
			return nil, err
		}
		for _, smp := range samples {
			sLen := smp.End - smp.Start
			if sLen <= 0 {
				continue
			}
			ivInt := new(big.Int).SetBytes(smp.IV[:])
			ctrOff := big.NewInt(smp.EncryptedOff / 16)
			ivInt.Add(ivInt, ctrOff)
			ivBytes := ivInt.FillBytes(make([]byte, 16))

			skip := int(smp.EncryptedOff % 16)
			stream := cipher.NewCTR(block, ivBytes)
			dummy := make([]byte, skip)
			stream.XORKeyStream(dummy, dummy)
			buf := make([]byte, sLen)
			copy(buf, data[smp.Start:smp.End])
			stream.XORKeyStream(buf, buf)
			copy(data[smp.Start:smp.End], buf)
		}
	}

	return &preparedVideo{header: data[:moov.end], samples: samples, total: int64(len(data))}, nil
}
