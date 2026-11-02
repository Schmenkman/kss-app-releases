/*
 * AP-Finder – Protokoll-Hilfen (Befehle parsen, JSON escapen, Kanallisten).
 */
#include "apf_proto.h"
#include "apf_parse.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

#define MAX_TOKENS 8
#define LINE_MAX_LEN 255

static void set_err(char *err, size_t errlen, const char *msg)
{
    if (!err || !errlen) {
        return;
    }
    size_t n = strlen(msg);
    if (n >= errlen) {
        n = errlen - 1;
    }
    memcpy(err, msg, n);
    err[n] = '\0';
}

static bool ieq(const char *a, const char *b)
{
    while (*a && *b) {
        if (toupper((unsigned char)*a) != toupper((unsigned char)*b)) {
            return false;
        }
        a++;
        b++;
    }
    return *a == '\0' && *b == '\0';
}

static int hexval(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

static bool parse_uint(const char *s, unsigned long max, unsigned long *out)
{
    if (!s || !*s) {
        return false;
    }
    unsigned long v = 0;
    for (const char *p = s; *p; p++) {
        if (*p < '0' || *p > '9') {
            return false;
        }
        v = v * 10 + (unsigned long)(*p - '0');
        if (v > max) {
            return false;
        }
    }
    *out = v;
    return true;
}

bool apf_parse_mac(const char *s, size_t n, uint8_t mac[6])
{
    int digits = 0;
    uint8_t tmp[6] = {0};
    for (size_t i = 0; i < n && s[i]; i++) {
        char c = s[i];
        if (c == ':' || c == '-' || c == '.') {
            continue;
        }
        int h = hexval(c);
        if (h < 0 || digits >= 12) {
            return false;
        }
        tmp[digits / 2] = (uint8_t)((tmp[digits / 2] << 4) | (uint8_t)h);
        digits++;
    }
    if (digits != 12) {
        return false;
    }
    memcpy(mac, tmp, 6);
    return true;
}

void apf_mac_to_str(const uint8_t mac[6], char *out)
{
    snprintf(out, 18, "%02x:%02x:%02x:%02x:%02x:%02x",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

size_t apf_json_escape(char *dst, size_t cap, const uint8_t *src, size_t len)
{
    static const char hex[] = "0123456789abcdef";
    size_t o = 0;
    if (!dst || cap == 0) {
        return 0;
    }
    for (size_t i = 0; i < len; i++) {
        const uint8_t c = src[i];
        char esc[6];
        size_t el;
        if (c == '"' || c == '\\') {
            esc[0] = '\\';
            esc[1] = (char)c;
            el = 2;
        } else if (c < 0x20 || c == 0x7F) {
            esc[0] = '\\';
            esc[1] = 'u';
            esc[2] = '0';
            esc[3] = '0';
            esc[4] = hex[c >> 4];
            esc[5] = hex[c & 0xF];
            el = 6;
        } else {
            esc[0] = (char)c;
            el = 1;
        }
        if (o + el >= cap) {
            break;
        }
        memcpy(dst + o, esc, el);
        o += el;
    }
    dst[o] = '\0';
    return o;
}

static const uint8_t CH_24[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
static const uint8_t CH_5[] = {36, 40, 44, 48, 52, 56, 60, 64,
                               100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140,
                               149, 153, 157, 161, 165};

size_t apf_scan_channels(uint8_t bands, uint8_t *out, size_t cap)
{
    size_t n = 0;
    if (bands & APF_BAND_2G) {
        for (size_t i = 0; i < sizeof(CH_24) && n < cap; i++) {
            out[n++] = CH_24[i];
        }
    }
    if (bands & APF_BAND_5G) {
        for (size_t i = 0; i < sizeof(CH_5) && n < cap; i++) {
            out[n++] = CH_5[i];
        }
    }
    return n;
}

static bool parse_channel(const char *s, uint8_t *ch)
{
    unsigned long v;
    if (!parse_uint(s, 200, &v) || apf_channel_band((uint8_t)v) == 0) {
        return false;
    }
    *ch = (uint8_t)v;
    return true;
}

static bool parse_ssid_hex(const char *s, uint8_t *out, uint8_t *out_len)
{
    size_t n = strlen(s);
    if (n % 2 != 0 || n / 2 > 32) {
        return false;
    }
    for (size_t i = 0; i < n; i += 2) {
        int hi = hexval(s[i]);
        int lo = hexval(s[i + 1]);
        if (hi < 0 || lo < 0) {
            return false;
        }
        out[i / 2] = (uint8_t)((hi << 4) | lo);
    }
    *out_len = (uint8_t)(n / 2);
    return true;
}

bool apf_cmd_parse(const char *line, apf_cmd_t *out, char *err, size_t errlen)
{
    if (!line || !out) {
        set_err(err, errlen, "intern");
        return false;
    }
    memset(out, 0, sizeof(*out));

    size_t len = strlen(line);
    if (len > LINE_MAX_LEN) {
        set_err(err, errlen, "Zeile zu lang");
        return false;
    }
    char buf[LINE_MAX_LEN + 1];
    memcpy(buf, line, len + 1);

    char *tok[MAX_TOKENS] = {0};
    int nt = 0;
    char *p = buf;
    while (*p && nt < MAX_TOKENS) {
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') {
            *p++ = '\0';
        }
        if (!*p) {
            break;
        }
        tok[nt++] = p;
        while (*p && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') {
            p++;
        }
    }
    *p = '\0';  /* letztes Token abschließen, auch wenn MAX_TOKENS erreicht */
    if (nt == 0) {
        set_err(err, errlen, "leer");
        return false;
    }

    const char *c = tok[0];
    if (ieq(c, "HELLO")) {
        out->type = APF_CMD_HELLO;
        return true;
    }
    if (ieq(c, "PING")) {
        out->type = APF_CMD_PING;
        return true;
    }
    if (ieq(c, "STOP")) {
        out->type = APF_CMD_STOP;
        return true;
    }
    if (ieq(c, "SCAN")) {
        out->type = APF_CMD_SCAN;
        out->dwell_ms = 110;
        out->bands = APF_BAND_2G | APF_BAND_5G;
        if (nt >= 2) {
            unsigned long v;
            if (!parse_uint(tok[1], 1000, &v) || v < 40) {
                set_err(err, errlen, "SCAN: Verweilzeit 40-1000 ms");
                return false;
            }
            out->dwell_ms = (uint16_t)v;
        }
        if (nt >= 3) {
            uint8_t b = 0;
            for (const char *q = tok[2]; *q; q++) {
                if (*q == '2') {
                    b |= APF_BAND_2G;
                } else if (*q == '5') {
                    b |= APF_BAND_5G;
                } else {
                    set_err(err, errlen, "SCAN: Baender 2, 5 oder 25");
                    return false;
                }
            }
            out->bands = b;
        }
        return true;
    }
    if (ieq(c, "TRACK")) {
        out->type = APF_CMD_TRACK;
        if (nt < 4) {
            set_err(err, errlen, "TRACK <kanal> <probe_hz> <bssid[,bssid]> [ssid_hex]");
            return false;
        }
        if (!parse_channel(tok[1], &out->channel)) {
            set_err(err, errlen, "TRACK: ungueltiger Kanal");
            return false;
        }
        unsigned long hz;
        if (!parse_uint(tok[2], 50, &hz)) {
            set_err(err, errlen, "TRACK: probe_hz 0-50");
            return false;
        }
        out->probe_hz = (uint8_t)hz;
        const char *s = tok[3];
        while (*s) {
            const char *e = strchr(s, ',');
            size_t n = e ? (size_t)(e - s) : strlen(s);
            if (out->n_bssid >= APF_MAX_TARGETS) {
                set_err(err, errlen, "TRACK: max. 8 BSSIDs");
                return false;
            }
            if (!apf_parse_mac(s, n, out->bssid[out->n_bssid])) {
                set_err(err, errlen, "TRACK: ungueltige BSSID");
                return false;
            }
            out->n_bssid++;
            if (!e) {
                break;
            }
            s = e + 1;
        }
        if (out->n_bssid == 0) {
            set_err(err, errlen, "TRACK: keine BSSID");
            return false;
        }
        if (nt >= 5 && !parse_ssid_hex(tok[4], out->ssid, &out->ssid_len)) {
            set_err(err, errlen, "TRACK: SSID als Hex (max. 32 Byte)");
            return false;
        }
        return true;
    }
    if (ieq(c, "FTM")) {
        out->type = APF_CMD_FTM;
        out->frames = 32;
        if (nt < 3) {
            set_err(err, errlen, "FTM <kanal> <bssid> [frames]");
            return false;
        }
        if (!parse_channel(tok[1], &out->channel)) {
            set_err(err, errlen, "FTM: ungueltiger Kanal");
            return false;
        }
        if (!apf_parse_mac(tok[2], strlen(tok[2]), out->bssid[0])) {
            set_err(err, errlen, "FTM: ungueltige BSSID");
            return false;
        }
        out->n_bssid = 1;
        if (nt >= 4) {
            unsigned long f;
            if (!parse_uint(tok[3], 64, &f) || (f != 0 && f != 16 && f != 24 && f != 32 && f != 64)) {
                set_err(err, errlen, "FTM: frames 0, 16, 24, 32 oder 64");
                return false;
            }
            out->frames = (uint8_t)f;
        }
        return true;
    }
    if (ieq(c, "DATA")) {
        out->type = APF_CMD_DATA;
        unsigned long v;
        if (nt < 2 || !parse_uint(tok[1], 1, &v)) {
            set_err(err, errlen, "DATA 0|1");
            return false;
        }
        out->flag = v != 0;
        return true;
    }
    set_err(err, errlen, "unbekannter Befehl");
    return false;
}
