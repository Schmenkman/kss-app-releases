/*
 * Host-Unit-Tests für apf_parse.c und apf_proto.c.
 * Baut 802.11-Frames von Hand und prüft die Auswertung.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "apf_parse.h"
#include "apf_proto.h"

static int g_fail = 0;
static int g_checks = 0;

#define CHECK(cond) do { \
    g_checks++; \
    if (!(cond)) { g_fail++; fprintf(stderr, "FEHLER %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
} while (0)

#define CHECK_STR(a, b) do { \
    g_checks++; \
    if (strcmp((a), (b)) != 0) { g_fail++; fprintf(stderr, "FEHLER %s:%d: \"%s\" != \"%s\"\n", __FILE__, __LINE__, (a), (b)); } \
} while (0)

/* ---- Frame-Baukasten ---- */
typedef struct {
    uint8_t buf[1024];
    size_t len;
} fb_t;

static const uint8_t BSSID[6] = {0x02, 0x11, 0x22, 0x33, 0x44, 0x55};

static void fb_mgmt(fb_t *f, uint8_t subtype, bool htc)
{
    memset(f, 0, sizeof(*f));
    f->buf[0] = (uint8_t)(subtype << 4);        /* Typ 0 = Management */
    f->buf[1] = htc ? 0x80 : 0x00;
    memset(f->buf + 4, 0xff, 6);                /* Addr1 Broadcast */
    memcpy(f->buf + 10, BSSID, 6);              /* Addr2 */
    memcpy(f->buf + 16, BSSID, 6);              /* Addr3 */
    f->len = htc ? 28 : 24;
    /* feste Felder: Timestamp(8), Intervall 100 TU, Capability */
    memset(f->buf + f->len, 0, 8);
    f->len += 8;
    f->buf[f->len++] = 100;
    f->buf[f->len++] = 0;
    f->buf[f->len++] = 0x01;                    /* ESS */
    f->buf[f->len++] = 0x00;
}

static void fb_cap_privacy(fb_t *f, bool htc)
{
    f->buf[(htc ? 28 : 24) + 10] |= 0x10;
}

static void fb_ie(fb_t *f, uint8_t id, const void *data, uint8_t len)
{
    f->buf[f->len++] = id;
    f->buf[f->len++] = len;
    memcpy(f->buf + f->len, data, len);
    f->len += len;
}

static void fb_vendor(fb_t *f, uint32_t oui, const void *rest, uint8_t rest_len)
{
    uint8_t tmp[255];
    tmp[0] = (uint8_t)(oui >> 16);
    tmp[1] = (uint8_t)(oui >> 8);
    tmp[2] = (uint8_t)oui;
    memcpy(tmp + 3, rest, rest_len);
    fb_ie(f, 221, tmp, (uint8_t)(rest_len + 3));
}

static bool parse_info(const fb_t *f, apf_bss_info_t *info)
{
    apf_frame_t fr;
    if (!apf_parse_frame(f->buf, (uint16_t)f->len, &fr)) {
        return false;
    }
    return apf_parse_bss_info(&fr, info);
}

/* ---- Tests ---- */
static void test_beacon_cisco(void)
{
    fb_t f;
    apf_bss_info_t info;
    fb_mgmt(&f, 8, false);
    fb_ie(&f, 0, "@BayernWLAN", 11);
    const uint8_t rates[] = {0x82, 0x84, 0x8b, 0x96};
    fb_ie(&f, 1, rates, sizeof(rates));
    const uint8_t ds[] = {6};
    fb_ie(&f, 3, ds, 1);
    const uint8_t tpc[] = {17, 0};
    fb_ie(&f, 35, tpc, 2);
    uint8_t htcap[26] = {0};
    fb_ie(&f, 45, htcap, sizeof(htcap));
    uint8_t ext[10] = {0};
    ext[8] = 0x40;                               /* Bit 70: FTM-Responder */
    fb_ie(&f, 127, ext, sizeof(ext));
    const char v2[] = "\x2f" "AP-Rathaus-OG2";
    fb_vendor(&f, 0x004096, v2, (uint8_t)(sizeof(v2) - 1));
    const uint8_t dtpc[] = {0x00, 20, 0x00};     /* DTPC 20 dBm – TPC hat Vorrang */
    fb_vendor(&f, 0x004096, dtpc, sizeof(dtpc));

    apf_frame_t fr;
    CHECK(apf_parse_frame(f.buf, (uint16_t)f.len, &fr));
    CHECK(fr.kind == APF_FRAME_BEACON);
    CHECK(memcmp(fr.ta, BSSID, 6) == 0);
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.ssid, "@BayernWLAN");
    CHECK(info.ssid_len == 11);
    CHECK(info.channel == 6);
    CHECK(info.tx_power == 17);
    CHECK(info.flags & APF_F_FTM_RESP);
    CHECK(info.flags & APF_F_HT);
    CHECK(!(info.flags & APF_F_PRIVACY));
    CHECK(!(info.flags & APF_F_RSN));
    CHECK(!(info.flags & APF_F_HIDDEN));
    CHECK_STR(info.name, "AP-Rathaus-OG2");
    CHECK(info.beacon_int == 100);
    CHECK(memcmp(info.bssid, BSSID, 6) == 0);
}

static void test_probe_resp_aruba_5g(void)
{
    fb_t f;
    apf_bss_info_t info;
    fb_mgmt(&f, 5, false);
    fb_cap_privacy(&f, false);
    fb_ie(&f, 0, "eduroam", 7);
    uint8_t htop[22] = {0};
    htop[0] = 36;
    fb_ie(&f, 61, htop, sizeof(htop));
    uint8_t rsn[20] = {1, 0};
    fb_ie(&f, 48, rsn, sizeof(rsn));
    uint8_t hecap[22] = {35};
    fb_ie(&f, 255, hecap, sizeof(hecap));
    const uint8_t aruba[] = {0x01, 0x03, 0x00, 'g', 'y', 'm', '-', '1', 0x00};
    fb_vendor(&f, 0x000B86, aruba, sizeof(aruba));
    const uint8_t dtpc[] = {0x00, 14, 0x00};     /* nur DTPC → wird genommen */
    fb_vendor(&f, 0x004096, dtpc, sizeof(dtpc));

    CHECK(parse_info(&f, &info));
    CHECK_STR(info.ssid, "eduroam");
    CHECK(info.channel == 36);
    CHECK(info.flags & APF_F_PRIVACY);
    CHECK(info.flags & APF_F_RSN);
    CHECK(info.flags & APF_F_HE);
    CHECK(!(info.flags & APF_F_FTM_RESP));
    CHECK_STR(info.name, "gym-1");
    CHECK(info.tx_power == 14);
}

static void test_hidden_and_names(void)
{
    fb_t f;
    apf_bss_info_t info;

    /* versteckte SSID (Länge 0) */
    fb_mgmt(&f, 8, false);
    fb_ie(&f, 0, "", 0);
    CHECK(parse_info(&f, &info));
    CHECK(info.flags & APF_F_HIDDEN);
    CHECK(info.ssid_len == 0);
    CHECK(info.tx_power == APF_TXP_UNKNOWN);
    CHECK(info.name[0] == '\0');

    /* versteckte SSID (Nullbytes) */
    fb_mgmt(&f, 8, false);
    const uint8_t zeros[8] = {0};
    fb_ie(&f, 0, zeros, sizeof(zeros));
    CHECK(parse_info(&f, &info));
    CHECK(info.flags & APF_F_HIDDEN);

    /* Ruckus: Typ 3 + Name */
    fb_mgmt(&f, 8, false);
    fb_ie(&f, 0, "x", 1);
    const uint8_t ruckus[] = {0x03, 'R', 'K', '-', '7'};
    fb_vendor(&f, 0x001392, ruckus, sizeof(ruckus));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "RK-7");

    /* Extreme: Subtyp 1, 7 Byte, Länge, Name */
    fb_mgmt(&f, 8, false);
    const uint8_t extreme[] = {0x01, 0, 0, 0, 0, 0, 0, 0, 4, 'E', 'X', '-', '1'};
    fb_vendor(&f, 0x00A0F8, extreme, sizeof(extreme));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "EX-1");

    /* Fortinet: Subtyp 10 (LE16), TLVs: Modell, Name */
    fb_mgmt(&f, 8, false);
    const uint8_t forti[] = {10, 0, 2, 3, 'F', 'A', 'P', 1, 4, 'F', 'G', '-', '9'};
    fb_vendor(&f, 0x00090F, forti, sizeof(forti));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "FG-9");

    /* Huawei: Typ, Subtyp 1, 1 Byte, Name */
    fb_mgmt(&f, 8, false);
    const uint8_t huawei[] = {0x01, 0x01, 0x00, 'H', 'W', '1'};
    fb_vendor(&f, 0x00E0FC, huawei, sizeof(huawei));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "HW1");

    /* Cisco CCX1 (IE 133): Name ab Offset 10, 16 Byte, NUL-gepolstert;
     * „AP name v2“ danach hat Vorrang */
    fb_mgmt(&f, 8, false);
    uint8_t ccx[30] = {0};
    memcpy(ccx + 10, "  old-name  ", 12);
    fb_ie(&f, 133, ccx, sizeof(ccx));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "old-name");
    const char v2[] = "\x2f" "new-name";
    fb_vendor(&f, 0x004096, v2, (uint8_t)(sizeof(v2) - 1));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "new-name");

    /* Nicht-ASCII im Namen wird zu '?' */
    fb_mgmt(&f, 8, false);
    const uint8_t mist[] = {0x01, 'A', 0xC3, 0xA4, 'B'};
    fb_vendor(&f, 0x5C5B35, mist, sizeof(mist));
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.name, "A??B");
}

static void test_htc_and_truncation(void)
{
    fb_t f;
    apf_bss_info_t info;

    /* Management-Frame mit +HTC: Body beginnt bei Offset 28 */
    fb_mgmt(&f, 8, true);
    fb_ie(&f, 0, "HTC", 3);
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.ssid, "HTC");

    /* abgeschnittenes Element: Parser stoppt, SSID davor bleibt */
    fb_mgmt(&f, 8, false);
    fb_ie(&f, 0, "AB", 2);
    f.buf[f.len++] = 221;
    f.buf[f.len++] = 200;          /* Länge größer als Rest */
    f.buf[f.len++] = 0x00;
    CHECK(parse_info(&f, &info));
    CHECK_STR(info.ssid, "AB");

    /* zu kurze Frames */
    apf_frame_t fr;
    CHECK(!apf_parse_frame(f.buf, 10, &fr));
    CHECK(!apf_parse_frame(NULL, 100, &fr));

    /* Beacon ohne feste Felder */
    fb_mgmt(&f, 8, false);
    CHECK(apf_parse_frame(f.buf, 30, &fr));
    CHECK(!apf_parse_bss_info(&fr, &info));
}

static void test_data_frames(void)
{
    uint8_t d[40] = {0};
    const uint8_t client[6] = {0xaa, 0xbb, 0xcc, 0x01, 0x02, 0x03};
    apf_frame_t fr;

    /* AP → Client (FromDS): Addr2 = BSSID */
    d[0] = 0x08;                   /* Typ 2 = Daten */
    d[1] = 0x02;                   /* FromDS */
    memcpy(d + 4, client, 6);
    memcpy(d + 10, BSSID, 6);
    memcpy(d + 16, client, 6);
    CHECK(apf_parse_frame(d, sizeof(d), &fr));
    CHECK(fr.kind == APF_FRAME_DATA);
    CHECK(memcmp(fr.ta, BSSID, 6) == 0);
    CHECK(memcmp(fr.bssid, BSSID, 6) == 0);

    /* Client → AP (ToDS): Sender ist der Client */
    d[1] = 0x01;
    memcpy(d + 4, BSSID, 6);
    memcpy(d + 10, client, 6);
    CHECK(apf_parse_frame(d, sizeof(d), &fr));
    CHECK(memcmp(fr.ta, client, 6) == 0);
    CHECK(memcmp(fr.bssid, BSSID, 6) == 0);

    /* Control-Frame (ACK) wird verworfen */
    d[0] = 0xD4;
    CHECK(!apf_parse_frame(d, sizeof(d), &fr));

    /* Daten sind kein BSS-Info */
    apf_bss_info_t info;
    d[0] = 0x08;
    CHECK(apf_parse_frame(d, sizeof(d), &fr));
    CHECK(!apf_parse_bss_info(&fr, &info));
}

static void test_fuzz(void)
{
    /* Zufallsframes dürfen nie abstürzen (ASan/UBSan prüft Zugriffe) */
    uint8_t buf[600];
    srand(1234);
    for (int i = 0; i < 200000; i++) {
        size_t len = (size_t)(rand() % (int)sizeof(buf));
        for (size_t j = 0; j < len; j++) {
            buf[j] = (uint8_t)rand();
        }
        if (len > 0 && (i & 1)) {
            buf[0] = (uint8_t)((rand() & 1) ? 0x80 : 0x50);   /* oft Beacon/Probe-Resp */
        }
        apf_frame_t fr;
        apf_bss_info_t info;
        if (apf_parse_frame(buf, (uint16_t)len, &fr)) {
            (void)apf_parse_bss_info(&fr, &info);
        }
        char line[300];
        size_t n = len < sizeof(line) - 1 ? len : sizeof(line) - 1;
        memcpy(line, buf, n);
        line[n] = '\0';
        apf_cmd_t cmd;
        char err[64];
        (void)apf_cmd_parse(line, &cmd, err, sizeof(err));
        char esc[64];
        (void)apf_json_escape(esc, sizeof(esc), buf, len);
    }
    CHECK(1);
}

static void test_cmd(void)
{
    apf_cmd_t c;
    char err[96];

    CHECK(apf_cmd_parse("HELLO", &c, err, sizeof(err)) && c.type == APF_CMD_HELLO);
    CHECK(apf_cmd_parse("  ping \r", &c, err, sizeof(err)) && c.type == APF_CMD_PING);
    CHECK(apf_cmd_parse("stop", &c, err, sizeof(err)) && c.type == APF_CMD_STOP);

    CHECK(apf_cmd_parse("SCAN", &c, err, sizeof(err)));
    CHECK(c.type == APF_CMD_SCAN && c.dwell_ms == 110 && c.bands == (APF_BAND_2G | APF_BAND_5G));
    CHECK(apf_cmd_parse("scan 200 5", &c, err, sizeof(err)));
    CHECK(c.dwell_ms == 200 && c.bands == APF_BAND_5G);
    CHECK(!apf_cmd_parse("SCAN 30", &c, err, sizeof(err)));
    CHECK(!apf_cmd_parse("SCAN 100 7", &c, err, sizeof(err)));

    CHECK(apf_cmd_parse("TRACK 36 10 aa:bb:cc:dd:ee:ff,11-22-33-44-55-66 4042617965726e574c414e", &c, err, sizeof(err)));
    CHECK(c.type == APF_CMD_TRACK && c.channel == 36 && c.probe_hz == 10 && c.n_bssid == 2);
    CHECK(c.bssid[0][0] == 0xaa && c.bssid[0][5] == 0xff);
    CHECK(c.bssid[1][0] == 0x11 && c.bssid[1][5] == 0x66);
    CHECK(c.ssid_len == 11 && memcmp(c.ssid, "@BayernWLAN", 11) == 0);
    CHECK(apf_cmd_parse("track 6 0 001122334455", &c, err, sizeof(err)));
    CHECK(c.channel == 6 && c.probe_hz == 0 && c.n_bssid == 1 && c.ssid_len == 0);
    CHECK(!apf_cmd_parse("TRACK 15 10 001122334455", &c, err, sizeof(err)));   /* Kanal 15 */
    CHECK(!apf_cmd_parse("TRACK 6 51 001122334455", &c, err, sizeof(err)));    /* > 50 Hz */
    CHECK(!apf_cmd_parse("TRACK 6 5 0011223344", &c, err, sizeof(err)));       /* MAC kurz */
    CHECK(!apf_cmd_parse("TRACK 6 5", &c, err, sizeof(err)));
    CHECK(!apf_cmd_parse("TRACK 6 5 001122334455 abc", &c, err, sizeof(err))); /* ungerades Hex */
    CHECK(!apf_cmd_parse("TRACK 6 5 0:1:2:3:4:5,0:1:2:3:4:5,0:1:2:3:4:5,0:1:2:3:4:5,"
                         "0:1:2:3:4:5,0:1:2:3:4:5,0:1:2:3:4:5,0:1:2:3:4:5,0:1:2:3:4:5", &c, err, sizeof(err)));

    CHECK(apf_cmd_parse("FTM 6 aabb.ccdd.eeff 16", &c, err, sizeof(err)));
    CHECK(c.type == APF_CMD_FTM && c.channel == 6 && c.frames == 16 && c.bssid[0][1] == 0xbb);
    CHECK(apf_cmd_parse("FTM 100 00:11:22:33:44:55", &c, err, sizeof(err)) && c.frames == 32);
    CHECK(!apf_cmd_parse("FTM 6 00:11:22:33:44:55 20", &c, err, sizeof(err)));
    CHECK(!apf_cmd_parse("FTM 6 xyz", &c, err, sizeof(err)));

    CHECK(apf_cmd_parse("DATA 1", &c, err, sizeof(err)) && c.type == APF_CMD_DATA && c.flag);
    CHECK(apf_cmd_parse("DATA 0", &c, err, sizeof(err)) && !c.flag);
    CHECK(!apf_cmd_parse("DATA 2", &c, err, sizeof(err)));

    CHECK(!apf_cmd_parse("foo", &c, err, sizeof(err)));
    CHECK_STR(err, "unbekannter Befehl");
    CHECK(!apf_cmd_parse("", &c, err, sizeof(err)));

    char longline[400];
    memset(longline, 'A', sizeof(longline) - 1);
    longline[sizeof(longline) - 1] = '\0';
    CHECK(!apf_cmd_parse(longline, &c, err, sizeof(err)));
}

static void test_mac_json_channels(void)
{
    uint8_t m[6];
    char s[18];
    CHECK(apf_parse_mac("AA-BB-CC-DD-EE-FF", 17, m) && m[0] == 0xaa && m[5] == 0xff);
    CHECK(apf_parse_mac("aabb.ccdd.eeff", 14, m) && m[2] == 0xcc);
    CHECK(!apf_parse_mac("aabbccddeeffaa", 14, m));
    CHECK(!apf_parse_mac("gg:bb:cc:dd:ee:ff", 17, m));
    apf_mac_to_str(m, s);
    CHECK_STR(s, "aa:bb:cc:dd:ee:ff");

    char out[64];
    const uint8_t in[] = {'a', '"', 'b', '\\', 0x01, 0xC3, 0xA4};
    size_t n = apf_json_escape(out, sizeof(out), in, sizeof(in));
    CHECK_STR(out, "a\\\"b\\\\\\u0001\xC3\xA4");
    CHECK(n == strlen(out));
    /* Abschneiden ohne halbes Escape */
    n = apf_json_escape(out, 5, in, sizeof(in));
    CHECK_STR(out, "a\\\"b");
    n = apf_json_escape(out, 6, (const uint8_t *)"\x01\x02", 2);
    CHECK_STR(out, "");

    uint8_t ch[64];
    CHECK(apf_scan_channels(APF_BAND_2G, ch, sizeof(ch)) == 13 && ch[0] == 1 && ch[12] == 13);
    CHECK(apf_scan_channels(APF_BAND_5G, ch, sizeof(ch)) == 24 && ch[0] == 36 && ch[23] == 165);
    CHECK(apf_scan_channels(APF_BAND_2G | APF_BAND_5G, ch, sizeof(ch)) == 37);
    CHECK(apf_scan_channels(APF_BAND_2G | APF_BAND_5G, ch, 10) == 10);
    CHECK(apf_channel_band(6) == 2 && apf_channel_band(36) == 5 && apf_channel_band(0) == 0 && apf_channel_band(15) == 0);
}

int main(void)
{
    test_beacon_cisco();
    test_probe_resp_aruba_5g();
    test_hidden_and_names();
    test_htc_and_truncation();
    test_data_frames();
    test_cmd();
    test_mac_json_channels();
    test_fuzz();
    if (g_fail) {
        fprintf(stderr, "%d von %d Pruefungen fehlgeschlagen\n", g_fail, g_checks);
        return 1;
    }
    printf("OK: %d Pruefungen bestanden\n", g_checks);
    return 0;
}
