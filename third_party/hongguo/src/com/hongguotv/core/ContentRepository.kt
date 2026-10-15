// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    // 取流接口固定设备参数（hongguo-dl 同款，2026-10-15 实测无需签名即可取流）
    private val STREAM_UA = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
    private val STREAM_QUERY = linkedMapOf(
        "klink_egdi" to "AAI29o4dI-eMiO73_SRSbZ_0By1v3fUSriNeu8-L951MoXhWT88pzj5B",
        "iid" to "3788260546453235", "device_id" to "538083340353620", "ac" to "wifi",
        "channel" to "oppo_8662_64", "aid" to "8662", "app_name" to "novelread",
        "version_code" to "71532", "version_name" to "7.1.5.32", "device_platform" to "android",
        "os" to "android", "ssmix" to "a", "device_type" to "SM-N9860", "device_brand" to "Samsung",
        "language" to "zh", "os_api" to "28", "os_version" to "9", "manifest_version_code" to "71532",
        "resolution" to "900*1600", "dpi" to "320", "update_version_code" to "71532",
        "host_abi" to "arm64-v8a", "dragon_device_type" to "pad", "pv_player" to "71532",
        "compliance_status" to "0", "need_personal_recommend" to "1", "player_so_load" to "1",
        "is_android_pad_screen" to "0", "rom_version" to "PQ3A.190705.10241111+release-keys",
        "cdid" to "b4f93387-5319-4134-aab9-2cd4e9279b8f"
    )
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The website search does not constrain content type. Never use it for comics.
        if(type==ContentType.COMIC) return appSearch(keyword,page,type)
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; /* Website fallback for short dramas only. */ }
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 红果取流接口（2026-10-15 变更）：video_model/v1 已对第三方签名静默返回空（风控 200 空 body）。
        // 改用 multi_video_model/preload/v1（固定设备参数 + 无需签名，hongguo-dl 同款，实测正常返回直链）。
        val biz = JSONObject()
            .put("detail_page_version", 0).put("device_level", 3).put("disable_digg_stat", false)
            .put("need_all_video_definition", true).put("need_mp4_align", false)
            .put("use_os_player", false).put("use_server_dns", false).put("video_platform", 1024)
        val payload = JSONObject()
            .put("biz_param", biz).put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
            .toString()
        val query = STREAM_QUERY.toMutableMap()
        query["_rticket"] = clock().toString()
        val queryStr = query.toList().joinToString("&") { (k, v) -> "$k=${Signer.encode(v)}" }
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?$queryStr"
        val request = Request.Builder().url(url)
            .header("User-Agent", STREAM_UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val data = JSONObject(RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            val input = body.byteStream(); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 16 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            val bytes = out.toByteArray()
            if (bytes.size > 16 * 1024 * 1024) throw IOException("内容响应过大")
            String(bytes, Charsets.UTF_8)
        })
        if (data.optInt("code", -1) != 0) throw IOException("播放接口暂不可用")
        val item = data.optJSONObject("data")?.optJSONObject(id) ?: throw IOException("播放信息为空")
        val model = item.optJSONObject("video_model") ?: JSONObject(item.str("video_model"))
        val rawList = model.opt("video_list")
        val variants = when (rawList) {
            is JSONArray -> (0 until rawList.length()).mapNotNull { rawList.optJSONObject(it) }
            is JSONObject -> rawList.keys().asSequence().mapNotNull { rawList.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        // ByteVC2 资源可能在 Android MP4 extractor 里只有音频没有视频轨，绝不选；只选有直链的清晰度。
        val rows = variants.filter { o ->
            o.str("main_url").isNotEmpty() && (o.optJSONObject("video_meta")?.str("codec_type") ?: "").lowercase() !in setOf("bytevc2", "bvc2")
        }.map { o ->
            val meta = o.optJSONObject("video_meta")
            val label = meta?.str("definition").orEmpty().ifEmpty { meta?.str("vheight").orEmpty() }
            Triple(qualityNumber(label), o, o.str("main_url"))
        }.sortedByDescending { it.first }
        if (rows.isEmpty()) throw IOException("没有可用清晰度")
        val selected = rows.firstOrNull { it.first <= maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val playUrl = cleanUrl(selected.third)
        if (!playUrl.startsWith("https://") && !playUrl.startsWith("http://")) throw IOException("播放地址无效")
        val key = selected.second.optJSONObject("encrypt_info")?.str("spade_a")?.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality = if (selected.first > 0) "${selected.first}P" else "自动"
        return StreamInfo(playUrl, key, quality)
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
