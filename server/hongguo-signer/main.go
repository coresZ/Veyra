package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/bits"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

const hongguoAppUserAgent = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
const hongguoAppBaseURL = "https://api5-normal-sinfonlineb.fqnovel.com"

func main() {
	http.HandleFunc("/play", handlePlay)
	fmt.Println("hongguo-signer listening on :18099")
	http.ListenAndServe("127.0.0.1:18099", nil)
}

func baseQuery() url.Values {
	return url.Values{
		"aid": {"8662"}, "app_name": {"novelread"}, "version_code": {"73532"},
		"version_name": {"7.3.5.32"}, "manifest_version_code": {"73532"},
		"update_version_code": {"73532"}, "channel": {"update_64"},
		"device_platform": {"android"}, "os": {"android"}, "ssmix": {"a"},
		"device_type": {"25053RT47C"}, "device_brand": {"Redmi"},
		"language": {"zh"}, "os_api": {"36"}, "os_version": {"16"},
		"resolution": {"1280*2772"}, "dpi": {"520"}, "ac": {"wifi"},
	}
}

func handlePlay(w http.ResponseWriter, r *http.Request) {
	vid := r.URL.Query().Get("vid")
	if vid == "" { http.Error(w, "missing vid", 400); return }
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	result, err := resolveVideo(ctx, vid)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(502)
		fmt.Fprintf(w, `{"error":%q}`, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

func resolveVideo(ctx context.Context, videoID string) (map[string]any, error) {
	deviceID, installID := newDeviceID(), newDeviceID()
	fmt.Printf("device=%s install=%s\n", deviceID, installID)

	query := baseQuery()
	query.Set("device_id", deviceID)
	query.Set("iid", installID)
	query.Set("_rticket", strconv.FormatInt(time.Now().UnixMilli(), 10))

	payload := map[string]any{
		"video_id": videoID, "content_type": 1,
		"biz_param": map[string]any{"need_all_video_definition": true, "video_platform": 3},
	}
	body, _ := json.Marshal(payload)
	rawQuery := query.Encode()

	stamp := uint32(time.Now().Unix())
	reqURL := hongguoAppBaseURL + "/novel/player/video_model/v1/?" + rawQuery
	req, _ := http.NewRequestWithContext(ctx, "POST", reqURL, bytes.NewReader(body))
	req.Header.Set("User-Agent", hongguoAppUserAgent)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-XS-From-Web", "0")
	req.Header.Set("Sdk-Version", "2")

	queryHash := md5.Sum([]byte(rawQuery))
	var signPayload [20]byte
	copy(signPayload[:4], queryHash[:4])
	bodyHash := md5.Sum(body)
	copy(signPayload[4:8], bodyHash[:4])
	req.Header.Set("X-SS-STUB", fmt.Sprintf("%X", bodyHash))
	copy(signPayload[12:16], []byte{0, 6, 11, 28})
	binary.BigEndian.PutUint32(signPayload[16:], stamp)
	signKey := [20]byte{0x44, 0xb9, 0xb9, 0xd9, 0xa4, 0xae, 0xf9, 0xfc, 0xa4, 0x93, 0xaa, 0x75, 0x7c, 0xa3, 0xc2, 0xc4, 0xa4, 0x96, 0x93, 0x8f}
	for i := range signPayload { signPayload[i] ^= signKey[i] }
	for i := range signPayload {
		mixed := bits.RotateLeft8(signPayload[i], 4) ^ signPayload[(i+1)%len(signPayload)]
		signPayload[i] = bits.Reverse8(mixed) ^ 0xff ^ byte(len(signPayload))
	}
	gorgon := append([]byte{0x84, 0x04, 0x40, 0x1c, 0, 0}, signPayload[:]...)
	req.Header.Set("X-Khronos", strconv.FormatUint(uint64(stamp), 10))
	req.Header.Set("X-Gorgon", hex.EncodeToString(gorgon))
	req.Header.Set("X-SS-Req-Ticket", strconv.FormatInt(time.Now().UnixMilli(), 10))

	nonce := make([]byte, 11)
	rand.Read(nonce)
	req.Header.Set("X-Argus", hongguoArgus(rawQuery, body, stamp, deviceID, nonce))
	req.Header.Set("X-Ladon", hongguoLadon("8662", stamp, nonce[2:6]))

	resp, err := http.DefaultClient.Do(req)
	if err != nil { return nil, err }
	defer resp.Body.Close()
	content, _ := io.ReadAll(io.LimitReader(resp.Body, 10<<20))
	fmt.Printf("fqnovel HTTP %d, body %d bytes: %.200s\n", resp.StatusCode, len(content), string(content))
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if len(content) == 0 {
		return nil, fmt.Errorf("空响应")
	}
	var result map[string]any
	json.Unmarshal(content, &result)
	return result, nil
}

func registerDevice(ctx context.Context) (string, string, error) {
	header := map[string]any{
		"display_name": "红果免费短剧", "aid": 8662, "app_version": "7.3.5.32", "version_code": 73532,
		"manifest_version_code": 73532, "update_version_code": 73532, "channel": "update_64", "package": "com.phoenix.read",
		"app_language": "zh", "os": "Android", "os_version": "16", "os_api": 36, "device_model": "25053RT47C",
		"device_brand": "Redmi", "device_manufacturer": "Xiaomi", "cpu_abi": "arm64-v8a", "density_dpi": 520,
		"display_density": "xxhdpi", "resolution": "2772x1280", "language": "zh", "timezone": 8, "access": "wifi",
		"region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800, "sim_region": "cn", "device_platform": "android",
	}
	body, _ := json.Marshal(map[string]any{"magic_tag": "ss_app_log", "header": header, "_gen_time": time.Now().UnixMilli()})
	q := baseQuery()
	q.Set("req_id", strconv.FormatInt(time.Now().UnixNano(), 10))
	q.Set("_rticket", strconv.FormatInt(time.Now().UnixMilli(), 10))
	req, _ := http.NewRequestWithContext(ctx, "POST", "https://log.snssdk.com/service/2/device_register/?"+q.Encode(), bytes.NewReader(body))
	req.Header.Set("User-Agent", hongguoAppUserAgent)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, err := http.DefaultClient.Do(req)
	if err != nil { return "", "", err }
	defer resp.Body.Close()
	content, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var result map[string]any
	json.Unmarshal(content, &result)
	deviceID := fmt.Sprintf("%v", result["device_id_str"])
	installID := fmt.Sprintf("%v", result["install_id_str"])
	if deviceID == "" || deviceID == "<nil>" || deviceID == "0" {
		return "", "", fmt.Errorf("注册失败: %s", string(content[:300]))
	}
	return deviceID, installID, nil
}

func hongguoSM3(input []byte) [32]byte {
	length := uint64(len(input)) * 8
	data := append(append([]byte(nil), input...), 0x80)
	for len(data)%64 != 56 { data = append(data, 0) }
	var lb [8]byte; binary.BigEndian.PutUint64(lb[:], length)
	data = append(data, lb[:]...)
	state := [8]uint32{0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e}
	for off := 0; off < len(data); off += 64 {
		var words [68]uint32
		for i := 0; i < 16; i++ { words[i] = binary.BigEndian.Uint32(data[off+i*4:]) }
		for i := 16; i < 68; i++ {
			v := words[i-16] ^ words[i-9] ^ bits.RotateLeft32(words[i-3], 15)
			words[i] = v ^ bits.RotateLeft32(v, 15) ^ bits.RotateLeft32(v, 23) ^ bits.RotateLeft32(words[i-13], 7) ^ words[i-6]
		}
		a,b,c,d,e,f,g,h := state[0],state[1],state[2],state[3],state[4],state[5],state[6],state[7]
		for i := 0; i < 64; i++ {
			t, ff, gg := uint32(0x79cc4519), a^b^c, e^f^g
			if i >= 16 { t, ff, gg = 0x7a879d8a, (a&b)|(a&c)|(b&c), (e&f)|(^e&g) }
			ss1 := bits.RotateLeft32(bits.RotateLeft32(a,12)+e+bits.RotateLeft32(t,i), 7)
			tt1 := ff + d + (ss1 ^ bits.RotateLeft32(a,12)) + (words[i] ^ words[i+4])
			tt2 := gg + h + ss1 + words[i]
			a,b,c,d = tt1, a, bits.RotateLeft32(b,9), c
			e,f,g,h = tt2^bits.RotateLeft32(tt2,9)^bits.RotateLeft32(tt2,17), e, bits.RotateLeft32(f,19), g
		}
		state[0]^=a; state[1]^=b; state[2]^=c; state[3]^=d; state[4]^=e; state[5]^=f; state[6]^=g; state[7]^=h
	}
	var result [32]byte
	for i, v := range state { binary.BigEndian.PutUint32(result[i*4:], v) }
	return result
}

func hongguoPad(data []byte) []byte {
	p := 16 - len(data)%16
	out := append([]byte(nil), data...)
	for i := 0; i < p; i++ { out = append(out, byte(p)) }
	return out
}

func hongguoArgus(query string, body []byte, timestamp uint32, deviceID string, nonce []byte) string {
	key, _ := hex.DecodeString("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
	varint := func(msg []byte, field, value uint64) []byte {
		msg = binary.AppendUvarint(msg, field*8)
		return binary.AppendUvarint(msg, value)
	}
	bytesField := func(msg []byte, field uint64, value []byte) []byte {
		msg = binary.AppendUvarint(msg, field*8+2)
		msg = binary.AppendUvarint(msg, uint64(len(value)))
		return append(msg, value...)
	}
	bodyHash := hongguoSM3(make([]byte, 16))
	if body != nil { stub := md5.Sum(body); bodyHash = hongguoSM3(stub[:]) }
	queryHash := hongguoSM3([]byte(query))
	counters := varint(varint(varint(varint(nil,1,1),2,1),3,1),7,3348294860)
	environment := bytesField(nil,1,[]byte("25053RT47C"))
	environment = bytesField(environment,2,[]byte("16"))
	environment = bytesField(environment,3,[]byte("update_64"))
	environment = varint(environment,4,2147483648)
	message := varint(nil,1,0x20200929*2)
	message = varint(message,2,2)
	message = bytesField(message,4,[]byte("8662"))
	message = bytesField(message,5,[]byte(deviceID))
	message = bytesField(message,6,[]byte("1611921764"))
	message = bytesField(message,7,[]byte("7.3.5.32"))
	message = bytesField(message,8,[]byte("v04.07.01-ml-android"))
	message = varint(message,9,135135744)
	message = bytesField(message,10,make([]byte,8))
	message = varint(message,11,0)
	message = varint(message,12,uint64(timestamp)*2)
	message = bytesField(message,13,bodyHash[:6])
	message = bytesField(message,14,queryHash[:6])
	message = bytesField(message,15,counters)
	message = bytesField(message,16,nil)
	message = bytesField(message,20,[]byte("none"))
	message = varint(message,21,738)
	message = bytesField(message,23,environment)
	message = varint(message,25,2)
	data := hongguoPad(message)
	seed := append(append(append([]byte(nil), key...), 0x3c,0xcc,0x56,0x7b), key...)
	derived := hongguoSM3(seed)
	var rounds [72]uint64
	for i := 0; i < 4; i++ { rounds[i] = binary.LittleEndian.Uint64(derived[i*8:]) }
	for i := 4; i < 72; i++ {
		mixed := bits.RotateLeft64(rounds[i-1], -3) ^ rounds[i-3]
		mixed ^= bits.RotateLeft64(mixed, -1)
		rounds[i] = ^rounds[i-4] ^ mixed ^ (uint64(0x3dc94c3a046d678b)>>uint((i-4)%62))&1 ^ 3
	}
	for off := 0; off < len(data); off += 16 {
		left, right := binary.LittleEndian.Uint64(data[off:]), binary.LittleEndian.Uint64(data[off+8:])
		for _, round := range rounds {
			nl, nr := right, left^(bits.RotateLeft64(right,1)&bits.RotateLeft64(right,8))^bits.RotateLeft64(right,2)^round
			left, right = nl, nr
		}
		binary.LittleEndian.PutUint64(data[off:], left)
		binary.LittleEndian.PutUint64(data[off+8:], right)
	}
	xor := []byte{0xd0,0x4f,0xfd,0xff,0xd0,0x4f,0xfd,0xff}
	for i := range data { data[i] ^= xor[i%8] }
	data = append(xor, data...)
	for l, r := 0, len(data)-1; l < r; l, r = l+1, r-1 { data[l], data[r] = data[r], data[l] }
	container := append([]byte{0xa6,0xe7,0x83,0xee,0x70,0x01,0x10,0x09,0x18}, data...)
	container = hongguoPad(append(container, 0x56, 0x7b))
	aesKey, iv := md5.Sum(key[:16]), md5.Sum(key[16:])
	block, _ := aes.NewCipher(aesKey[:])
	cipher.NewCBCEncrypter(block, iv[:]).CryptBlocks(container, container)
	return base64.StdEncoding.EncodeToString(append([]byte{0x3c,0xcc}, container...))
}

func hongguoLadon(aid string, timestamp uint32, prefix []byte) string {
	hash := md5.Sum(append(append([]byte(nil), prefix...), aid...))
	digest := []byte(hex.EncodeToString(hash[:]))
	left, right := binary.LittleEndian.Uint64(digest), binary.LittleEndian.Uint64(digest[8:])
	queue := []uint64{binary.LittleEndian.Uint64(digest[16:]), binary.LittleEndian.Uint64(digest[24:])}
	var keys [34]uint64
	for i := range keys {
		keys[i] = left
		mixed := (bits.RotateLeft64(right, -8) + left) ^ uint64(i)
		queue = append(queue, mixed)
		left = mixed ^ bits.RotateLeft64(left, 3)
		right, queue = queue[0], queue[1:]
	}
	data := hongguoPad([]byte(strconv.FormatUint(uint64(timestamp),10)+"-1611921764-"+aid))
	for off := 0; off < len(data); off += 16 {
		lv, rv := binary.LittleEndian.Uint64(data[off:]), binary.LittleEndian.Uint64(data[off+8:])
		for _, k := range keys {
			rv = k ^ (lv + bits.RotateLeft64(rv, -8))
			lv = rv ^ bits.RotateLeft64(lv, 3)
		}
		binary.LittleEndian.PutUint64(data[off:], lv)
		binary.LittleEndian.PutUint64(data[off+8:], rv)
	}
	return base64.StdEncoding.EncodeToString(append(prefix, data...))
}

func newDeviceID() string {
	var r [8]byte
	rand.Read(r[:])
	return strconv.FormatUint(1000000000000000000+binary.BigEndian.Uint64(r[:])%8000000000000000000, 10)
}
