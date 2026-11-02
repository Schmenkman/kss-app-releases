/*
 * AP-Finder – Firmware für ESP32-C5 (ESP-IDF v6.0, kompatibel zu v5.5)
 *
 * Der ESP32-C5 ist der Funk-Messkopf: Er hört im Promiscuous-Modus Beacons,
 * Probe Responses (und optional Datenframes) mit und meldet die Pegel als
 * JSON-Zeilen über USB (USB-Serial/JTAG, am Handy ein CDC-Gerät).
 *
 * Betriebsarten:
 *   SCAN  – Kanal-Hopping über 2,4 und/oder 5 GHz, je BSSID ein Datensatz
 *           pro Verweilzeit (SSID, Kanal, Pegel, FTM, Sendeleistung, AP-Name).
 *   TRACK – fest auf einem Kanal, misst bis zu 8 BSSIDs (ein Funkmodul,
 *           mehrere SSIDs) und schickt alle 100 ms Mittelwert/Max/Anzahl.
 *           Optional aktive Probe Requests an die BSSIDs (mehr Messwerte).
 *   FTM   – einmalige Laufzeitmessung (802.11mc), wenn der AP FTM-Responder ist.
 *
 * Protokoll: docs/PROTOKOLL.md
 */
#include <inttypes.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "driver/usb_serial_jtag.h"
#include "esp_event.h"
#include "esp_idf_version.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "nvs_flash.h"

#include "apf_parse.h"
#include "apf_proto.h"

#define FW_VERSION      "0.1.0"
#define TAG             "apf"

#define RXQ_LEN         64
#define CMDQ_LEN        4
#define SCAN_MAX        96          /* BSSIDs pro Kanal-Verweilzeit */
#define TRACK_EMIT_MS   100         /* Aggregationsfenster im TRACK-Modus */
#define IDLE_STATUS_MS  2000
#define FTM_TIMEOUT_MS  5000
#define OUT_LINE_MAX    640

typedef enum {
    MODE_IDLE = 0,
    MODE_SCAN,
    MODE_TRACK,
    MODE_FTM,
} apf_mode_t;

enum { ITEM_BSS = 1, ITEM_SAMPLE = 2 };

typedef struct {
    uint8_t  type;
    uint8_t  kind;          /* apf_frame_kind_t */
    uint8_t  idx;           /* Ziel-Index (Samples) */
    uint8_t  ch;            /* eingestellter Kanal beim Empfang */
    int8_t   rssi;
    int8_t   noise;
    uint32_t ts_us;
    apf_bss_info_t bss;     /* nur ITEM_BSS */
} rx_item_t;

typedef struct {
    apf_bss_info_t bss;
    int8_t   best_rssi;
    uint16_t count;
} scan_entry_t;

typedef struct {
    int32_t sum;
    uint16_t n;
    int8_t max;
    int8_t min;
} agg_t;

/* ---- gemeinsamer Zustand (Callback liest nur volatile Felder) ---- */
static volatile apf_mode_t s_mode = MODE_IDLE;
static volatile uint8_t    s_cur_ch = 0;
static volatile uint8_t    s_n_targets = 0;
static volatile bool       s_count_data = false;
static uint8_t             s_targets[APF_MAX_TARGETS][6];

static QueueHandle_t       s_rxq;
static QueueHandle_t       s_cmdq;
static SemaphoreHandle_t   s_out_mtx;
static EventGroupHandle_t  s_evt;
#define EVT_FTM_DONE BIT0

static uint8_t             s_own_mac[6];
static volatile uint32_t   s_rx_dropped = 0;

/* FTM-Ergebnis aus dem Event-Handler */
static struct {
    uint8_t  peer[6];
    int      status;
    uint32_t rtt_raw;
    uint32_t rtt_est;
    uint32_t dist_cm;
    uint8_t  entries;
} s_ftm;

/* ---- nur im Verarbeitungs-Task benutzt ---- */
static struct {
    /* SCAN */
    uint8_t  chans[48];
    size_t   n_chans;
    size_t   chan_idx;
    uint16_t dwell_ms;
    int64_t  next_hop_ms;
    int64_t  sweep_start_ms;
    uint32_t sweep_no;
    scan_entry_t tab[SCAN_MAX];
    int      tab_n;
    uint32_t sweep_bss;
    /* TRACK */
    uint8_t  track_ch;
    uint8_t  probe_hz;
    uint8_t  ssid[32];
    uint8_t  ssid_len;
    uint8_t  probe_rr;
    int64_t  next_emit_ms;
    int64_t  next_probe_ms;
    agg_t    ab;            /* Beacons + Probe Responses */
    agg_t    ad;            /* Datenframes + sonstige Mgmt-Frames */
    int32_t  nf_sum;
    uint16_t nf_n;
    uint16_t tx_ok;
    uint16_t tx_err;
    uint16_t per_idx[APF_MAX_TARGETS];
    /* IDLE */
    int64_t  next_status_ms;
} P;

static inline int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

/* ------------------------------------------------------------------ */
/* Ausgabe: eine JSON-Zeile pro Aufruf über USB-Serial/JTAG             */
/* ------------------------------------------------------------------ */
static void out_line(const char *fmt, ...)
{
    char buf[OUT_LINE_MAX];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 1, fmt, ap);
    va_end(ap);
    if (n < 0) {
        return;
    }
    if (n > (int)sizeof(buf) - 2) {
        n = (int)sizeof(buf) - 2;
    }
    buf[n++] = '\n';
    if (!usb_serial_jtag_is_connected()) {
        return;
    }
    if (xSemaphoreTake(s_out_mtx, pdMS_TO_TICKS(50)) == pdTRUE) {
        usb_serial_jtag_write_bytes(buf, (size_t)n, pdMS_TO_TICKS(5));
        xSemaphoreGive(s_out_mtx);
    }
}

static void out_err(const char *msg)
{
    char esc[128];
    apf_json_escape(esc, sizeof(esc), (const uint8_t *)msg, strlen(msg));
    out_line("{\"t\":\"err\",\"msg\":\"%s\"}", esc);
}

static void out_hello(void)
{
    char mac[18];
    apf_mac_to_str(s_own_mac, mac);
    out_line("{\"t\":\"hello\",\"fw\":\"%s\",\"chip\":\"%s\",\"idf\":\"%s\",\"mac\":\"%s\"}",
             FW_VERSION, CONFIG_IDF_TARGET, esp_get_idf_version(), mac);
}

/* ------------------------------------------------------------------ */
/* Funk                                                                */
/* ------------------------------------------------------------------ */
static esp_err_t tune(uint8_t ch)
{
    esp_err_t e = esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
    if (e != ESP_OK) {
        /* Fallback: Bandmodus explizit setzen und erneut versuchen */
        wifi_band_mode_t bm = (ch <= 14) ? WIFI_BAND_MODE_2G_ONLY : WIFI_BAND_MODE_5G_ONLY;
        if (esp_wifi_set_band_mode(bm) == ESP_OK) {
            e = esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
        }
    }
    if (e == ESP_OK) {
        s_cur_ch = ch;
    }
    return e;
}

static void promisc_cb(void *buf, wifi_promiscuous_pkt_type_t type)
{
    if (type == WIFI_PKT_MISC) {
        return;
    }
    const apf_mode_t mode = s_mode;
    if (mode != MODE_SCAN && mode != MODE_TRACK) {
        return;
    }
    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    if (pkt->rx_ctrl.rx_state != 0) {
        return;                                    /* fehlerhafter Frame */
    }
    const int len = (int)pkt->rx_ctrl.sig_len - 4; /* ohne FCS */
    if (len < 24) {
        return;
    }
    /* Frame-Typ selbst aus dem Header bestimmen: der Callback-Typ ist beim
     * C5 auf 5 GHz nicht zuverlässig. */
    apf_frame_t f;
    if (!apf_parse_frame(pkt->payload, (uint16_t)len, &f)) {
        return;
    }

    rx_item_t it;
    it.kind = (uint8_t)f.kind;
    it.ch = s_cur_ch;
    it.rssi = (int8_t)pkt->rx_ctrl.rssi;
    it.noise = (int8_t)pkt->rx_ctrl.noise_floor;
    it.ts_us = pkt->rx_ctrl.timestamp;
    it.idx = 0;

    if (mode == MODE_SCAN) {
        if (f.kind != APF_FRAME_BEACON && f.kind != APF_FRAME_PROBE_RESP) {
            return;
        }
        if (!apf_parse_bss_info(&f, &it.bss)) {
            return;
        }
        it.type = ITEM_BSS;
    } else {
        if (!f.ta) {
            return;
        }
        if (f.kind == APF_FRAME_DATA && !s_count_data) {
            return;
        }
        const uint8_t n = s_n_targets;
        int idx = -1;
        for (uint8_t i = 0; i < n; i++) {
            if (memcmp(f.ta, s_targets[i], 6) == 0) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        it.type = ITEM_SAMPLE;
        it.idx = (uint8_t)idx;
    }
    if (xQueueSend(s_rxq, &it, 0) != pdTRUE) {
        s_rx_dropped++;
    }
}

static void wifi_event_handler(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_FTM_REPORT && data) {
        const wifi_event_ftm_report_t *r = (const wifi_event_ftm_report_t *)data;
        memcpy(s_ftm.peer, r->peer_mac, 6);
        s_ftm.status = (int)r->status;
        s_ftm.rtt_raw = r->rtt_raw;
        s_ftm.rtt_est = r->rtt_est;
        s_ftm.dist_cm = r->dist_est;
        s_ftm.entries = r->ftm_report_num_entries;
        xEventGroupSetBits(s_evt, EVT_FTM_DONE);
    }
}

/* Gerichteter Probe Request an eine BSSID → der AP antwortet mit einer
 * Probe Response = zusätzlicher Messwert. */
static void send_probe(const uint8_t *bssid, uint8_t ch)
{
    uint8_t f[24 + 2 + 32 + 2 + 8 + 2 + 4];
    size_t o = 0;
    f[o++] = 0x40;                      /* FC: Management, Subtyp 4 = Probe Request */
    f[o++] = 0x00;
    f[o++] = 0x00;                      /* Duration */
    f[o++] = 0x00;
    memcpy(f + o, bssid, 6);            /* Addr1: Ziel */
    o += 6;
    memcpy(f + o, s_own_mac, 6);        /* Addr2: wir */
    o += 6;
    memcpy(f + o, bssid, 6);            /* Addr3: BSSID */
    o += 6;
    f[o++] = 0x00;                      /* Sequenz (setzt der Treiber) */
    f[o++] = 0x00;
    f[o++] = 0x00;                      /* SSID-Element */
    f[o++] = P.ssid_len;
    memcpy(f + o, P.ssid, P.ssid_len);
    o += P.ssid_len;
    if (ch <= 14) {
        static const uint8_t rates[] = {0x01, 0x08, 0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24,
                                        0x32, 0x04, 0x30, 0x48, 0x60, 0x6c};
        memcpy(f + o, rates, sizeof(rates));
        o += sizeof(rates);
    } else {
        static const uint8_t rates[] = {0x01, 0x08, 0x8c, 0x12, 0x98, 0x24, 0xb0, 0x48, 0x60, 0x6c};
        memcpy(f + o, rates, sizeof(rates));
        o += sizeof(rates);
    }
    if (esp_wifi_80211_tx(WIFI_IF_STA, f, (int)o, true) == ESP_OK) {
        P.tx_ok++;
    } else {
        P.tx_err++;
    }
}

/* ------------------------------------------------------------------ */
/* SCAN                                                                */
/* ------------------------------------------------------------------ */
static void scan_flush(void)
{
    char ssid[3 * APF_SSID_MAX * 2 + 1];
    char name[APF_NAME_MAX * 6 + 1];
    char mac[18];
    char txp[8];
    for (int i = 0; i < P.tab_n; i++) {
        const scan_entry_t *e = &P.tab[i];
        const apf_bss_info_t *b = &e->bss;
        apf_mac_to_str(b->bssid, mac);
        apf_json_escape(ssid, sizeof(ssid), (const uint8_t *)b->ssid, b->ssid_len);
        apf_json_escape(name, sizeof(name), (const uint8_t *)b->name, strlen(b->name));
        if (b->tx_power != APF_TXP_UNKNOWN) {
            snprintf(txp, sizeof(txp), "%d", b->tx_power);
        } else {
            snprintf(txp, sizeof(txp), "null");
        }
        const char *enc = (b->flags & APF_F_RSN) ? "rsn" : ((b->flags & APF_F_PRIVACY) ? "wep" : "open");
        const char *std = (b->flags & APF_F_HE) ? "ax" : (b->flags & APF_F_VHT) ? "ac" : (b->flags & APF_F_HT) ? "n" : "";
        out_line("{\"t\":\"ap\",\"b\":\"%s\",\"s\":\"%s\",\"h\":%d,\"ch\":%u,\"r\":%d,\"n\":%u,"
                 "\"txp\":%s,\"ftm\":%d,\"enc\":\"%s\",\"std\":\"%s\",\"nm\":\"%s\",\"bi\":%u}",
                 mac, ssid, (b->flags & APF_F_HIDDEN) ? 1 : 0, b->channel, e->best_rssi, e->count,
                 txp, (b->flags & APF_F_FTM_RESP) ? 1 : 0, enc, std, name, b->beacon_int);
    }
    P.sweep_bss += (uint32_t)P.tab_n;
    P.tab_n = 0;
}

static void scan_add(const rx_item_t *it)
{
    apf_bss_info_t b = it->bss;
    /* Beacons von Nachbarkanälen (2,4 GHz überlappt) ignorieren – der AP
     * wird auf seinem eigenen Kanal mit vollem Pegel gemessen. */
    if (b.channel != 0 && b.channel != it->ch) {
        return;
    }
    if (b.channel == 0) {
        b.channel = it->ch;
    }
    for (int i = 0; i < P.tab_n; i++) {
        scan_entry_t *e = &P.tab[i];
        if (memcmp(e->bss.bssid, b.bssid, 6) == 0) {
            if (it->rssi > e->best_rssi) {
                e->best_rssi = it->rssi;
            }
            e->count++;
            /* Name/Sendeleistung können in Probe Responses vollständiger sein */
            if (!e->bss.name[0] && b.name[0]) {
                memcpy(e->bss.name, b.name, sizeof(b.name));
            }
            if (e->bss.tx_power == APF_TXP_UNKNOWN) {
                e->bss.tx_power = b.tx_power;
            }
            if (!e->bss.ssid_len && b.ssid_len) {
                memcpy(e->bss.ssid, b.ssid, sizeof(b.ssid));
                e->bss.ssid_len = b.ssid_len;
                e->bss.flags &= (uint8_t)~APF_F_HIDDEN;
            }
            e->bss.flags |= (uint8_t)(b.flags & (APF_F_FTM_RESP | APF_F_HT | APF_F_VHT | APF_F_HE | APF_F_RSN));
            return;
        }
    }
    if (P.tab_n < SCAN_MAX) {
        scan_entry_t *e = &P.tab[P.tab_n++];
        e->bss = b;
        e->best_rssi = it->rssi;
        e->count = 1;
    }
}

static void scan_start(uint16_t dwell_ms, uint8_t bands)
{
    s_mode = MODE_IDLE;
    P.n_chans = apf_scan_channels(bands, P.chans, sizeof(P.chans));
    P.chan_idx = 0;
    P.dwell_ms = dwell_ms;
    P.tab_n = 0;
    P.sweep_bss = 0;
    P.sweep_no = 0;
    xQueueReset(s_rxq);
    while (P.chan_idx < P.n_chans && tune(P.chans[P.chan_idx]) != ESP_OK) {
        P.chan_idx++;
    }
    if (P.chan_idx >= P.n_chans) {
        out_err("SCAN: kein Kanal einstellbar");
        return;
    }
    P.sweep_start_ms = now_ms();
    P.next_hop_ms = P.sweep_start_ms + P.dwell_ms;
    s_mode = MODE_SCAN;
    out_line("{\"t\":\"ack\",\"cmd\":\"SCAN\",\"dwell\":%u,\"nch\":%u}", P.dwell_ms, (unsigned)P.n_chans);
}

static void scan_hop(int64_t now)
{
    scan_flush();
    /* nächsten einstellbaren Kanal suchen */
    for (size_t tries = 0; tries < P.n_chans; tries++) {
        P.chan_idx++;
        if (P.chan_idx >= P.n_chans) {
            P.chan_idx = 0;
            P.sweep_no++;
            out_line("{\"t\":\"sweep\",\"no\":%" PRIu32 ",\"ms\":%" PRIu32 ",\"bss\":%" PRIu32 ",\"drop\":%" PRIu32 "}",
                     P.sweep_no, (uint32_t)(now - P.sweep_start_ms), P.sweep_bss, s_rx_dropped);
            P.sweep_start_ms = now;
            P.sweep_bss = 0;
        }
        if (tune(P.chans[P.chan_idx]) == ESP_OK) {
            break;
        }
    }
    P.next_hop_ms = now + P.dwell_ms;
}

/* ------------------------------------------------------------------ */
/* TRACK                                                               */
/* ------------------------------------------------------------------ */
static void agg_reset(agg_t *a)
{
    a->sum = 0;
    a->n = 0;
    a->max = -128;
    a->min = 127;
}

static void agg_add(agg_t *a, int8_t v)
{
    a->sum += v;
    a->n++;
    if (v > a->max) {
        a->max = v;
    }
    if (v < a->min) {
        a->min = v;
    }
}

static void track_reset_window(void)
{
    agg_reset(&P.ab);
    agg_reset(&P.ad);
    P.nf_sum = 0;
    P.nf_n = 0;
    P.tx_ok = 0;
    P.tx_err = 0;
    memset(P.per_idx, 0, sizeof(P.per_idx));
}

static void track_start(const apf_cmd_t *c)
{
    s_mode = MODE_IDLE;
    s_n_targets = 0;
    memcpy(s_targets, c->bssid, sizeof(s_targets));
    P.track_ch = c->channel;
    P.probe_hz = c->probe_hz;
    P.ssid_len = c->ssid_len;
    memcpy(P.ssid, c->ssid, sizeof(P.ssid));
    P.probe_rr = 0;
    xQueueReset(s_rxq);
    esp_err_t e = tune(c->channel);
    if (e != ESP_OK) {
        char msg[64];
        snprintf(msg, sizeof(msg), "TRACK: Kanal %u nicht einstellbar (0x%x)", c->channel, (unsigned)e);
        out_err(msg);
        return;
    }
    track_reset_window();
    int64_t now = now_ms();
    P.next_emit_ms = now + TRACK_EMIT_MS;
    P.next_probe_ms = now;
    s_n_targets = c->n_bssid;
    s_mode = MODE_TRACK;
    out_line("{\"t\":\"ack\",\"cmd\":\"TRACK\",\"ch\":%u,\"n\":%u,\"probe\":%u}",
             c->channel, c->n_bssid, c->probe_hz);
}

/* Mittelwert mit einer Nachkommastelle ohne Float-printf (libc-unabhängig) */
static void fmt_avg(char *buf, size_t cap, int32_t sum, uint16_t n)
{
    const int32_t half = (int32_t)n / 2;
    const int32_t v10 = (sum * 10 + (sum >= 0 ? half : -half)) / (int32_t)n;
    const int32_t a = v10 < 0 ? -v10 : v10;
    snprintf(buf, cap, "%s%ld.%ld", v10 < 0 ? "-" : "", (long)(a / 10), (long)(a % 10));
}

static void track_emit(int64_t now)
{
    char rb[16], xb[8], mb[8], rd[16], xd[8], nf[8];
    if (P.ab.n) {
        fmt_avg(rb, sizeof(rb), P.ab.sum, P.ab.n);
        snprintf(xb, sizeof(xb), "%d", P.ab.max);
        snprintf(mb, sizeof(mb), "%d", P.ab.min);
    } else {
        strcpy(rb, "null");
        strcpy(xb, "null");
        strcpy(mb, "null");
    }
    if (P.ad.n) {
        fmt_avg(rd, sizeof(rd), P.ad.sum, P.ad.n);
        snprintf(xd, sizeof(xd), "%d", P.ad.max);
    } else {
        strcpy(rd, "null");
        strcpy(xd, "null");
    }
    if (P.nf_n) {
        snprintf(nf, sizeof(nf), "%d", (int)(P.nf_sum / P.nf_n));
    } else {
        strcpy(nf, "null");
    }
    out_line("{\"t\":\"rssi\",\"ms\":%" PRIu32 ",\"ch\":%u,\"nb\":%u,\"rb\":%s,\"xb\":%s,\"mb\":%s,"
             "\"nd\":%u,\"rd\":%s,\"xd\":%s,\"nf\":%s,\"tx\":%u,\"te\":%u}",
             (uint32_t)now, P.track_ch, P.ab.n, rb, xb, mb, P.ad.n, rd, xd, nf, P.tx_ok, P.tx_err);
    track_reset_window();
}

static void track_add(const rx_item_t *it)
{
    if (it->kind == APF_FRAME_BEACON || it->kind == APF_FRAME_PROBE_RESP) {
        agg_add(&P.ab, it->rssi);
    } else {
        agg_add(&P.ad, it->rssi);
    }
    if (it->noise < 0) {
        P.nf_sum += it->noise;
        P.nf_n++;
    }
    if (it->idx < APF_MAX_TARGETS) {
        P.per_idx[it->idx]++;
    }
}

/* ------------------------------------------------------------------ */
/* FTM                                                                 */
/* ------------------------------------------------------------------ */
static void ftm_run(const apf_cmd_t *c)
{
    const apf_mode_t prev = s_mode;
    const uint8_t prev_ch = s_cur_ch;
    char mac[18];
    apf_mac_to_str(c->bssid[0], mac);

    s_mode = MODE_FTM;
    esp_wifi_set_promiscuous(false);

    wifi_ftm_initiator_cfg_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    memcpy(cfg.resp_mac, c->bssid[0], 6);
    cfg.channel = c->channel;
    cfg.frm_count = c->frames;
    cfg.burst_period = 2;
#if ESP_IDF_VERSION < ESP_IDF_VERSION_VAL(6, 0, 0)
    cfg.use_get_report_api = true;
#endif
    xEventGroupClearBits(s_evt, EVT_FTM_DONE);
    esp_err_t e = esp_wifi_ftm_initiate_session(&cfg);
    if (e != ESP_OK) {
        out_line("{\"t\":\"ftm\",\"b\":\"%s\",\"ok\":false,\"err\":\"start 0x%x\"}", mac, (unsigned)e);
    } else {
        EventBits_t bits = xEventGroupWaitBits(s_evt, EVT_FTM_DONE, pdTRUE, pdFALSE,
                                               pdMS_TO_TICKS(FTM_TIMEOUT_MS));
        if (bits & EVT_FTM_DONE) {
            const bool ok = (s_ftm.status == FTM_STATUS_SUCCESS);
            out_line("{\"t\":\"ftm\",\"b\":\"%s\",\"ok\":%s,\"status\":%d,\"dist_cm\":%" PRIu32
                     ",\"rtt_ns\":%" PRIu32 ",\"rtt_raw\":%" PRIu32 ",\"n\":%u}",
                     mac, ok ? "true" : "false", s_ftm.status, s_ftm.dist_cm,
                     s_ftm.rtt_est, s_ftm.rtt_raw, s_ftm.entries);
        } else {
            esp_wifi_ftm_end_session();
            out_line("{\"t\":\"ftm\",\"b\":\"%s\",\"ok\":false,\"err\":\"timeout\"}", mac);
        }
        esp_wifi_ftm_get_report(NULL, 0);   /* internen Report freigeben */
    }

    esp_wifi_set_promiscuous(true);
    if (prev == MODE_TRACK) {
        tune(P.track_ch);
        track_reset_window();
        P.next_emit_ms = now_ms() + TRACK_EMIT_MS;
    } else if (prev_ch) {
        tune(prev_ch);
        if (prev == MODE_SCAN) {
            P.next_hop_ms = now_ms() + P.dwell_ms;
        }
    }
    xQueueReset(s_rxq);
    s_mode = prev;
}

/* ------------------------------------------------------------------ */
/* Tasks                                                               */
/* ------------------------------------------------------------------ */
static void handle_cmd(const apf_cmd_t *c)
{
    switch (c->type) {
    case APF_CMD_HELLO:
        out_hello();
        break;
    case APF_CMD_PING:
        out_line("{\"t\":\"pong\",\"ms\":%" PRIu32 ",\"mode\":%d}", (uint32_t)now_ms(), (int)s_mode);
        break;
    case APF_CMD_STOP:
        s_mode = MODE_IDLE;
        s_n_targets = 0;
        out_line("{\"t\":\"ack\",\"cmd\":\"STOP\"}");
        break;
    case APF_CMD_SCAN:
        scan_start(c->dwell_ms, c->bands);
        break;
    case APF_CMD_TRACK:
        track_start(c);
        break;
    case APF_CMD_FTM:
        ftm_run(c);
        break;
    case APF_CMD_DATA:
        s_count_data = c->flag;
        out_line("{\"t\":\"ack\",\"cmd\":\"DATA\",\"on\":%d}", c->flag ? 1 : 0);
        break;
    default:
        break;
    }
}

static void proc_task(void *arg)
{
    rx_item_t it;
    apf_cmd_t cmd;
    P.next_status_ms = now_ms() + IDLE_STATUS_MS;

    for (;;) {
        while (xQueueReceive(s_cmdq, &cmd, 0) == pdTRUE) {
            handle_cmd(&cmd);
        }

        if (xQueueReceive(s_rxq, &it, pdMS_TO_TICKS(10)) == pdTRUE) {
            int budget = RXQ_LEN;
            do {
                const apf_mode_t m = s_mode;
                if (it.type == ITEM_BSS && m == MODE_SCAN) {
                    scan_add(&it);
                } else if (it.type == ITEM_SAMPLE && m == MODE_TRACK) {
                    track_add(&it);
                }
            } while (--budget > 0 && xQueueReceive(s_rxq, &it, 0) == pdTRUE);
        }

        const int64_t now = now_ms();
        switch (s_mode) {
        case MODE_SCAN:
            if (now >= P.next_hop_ms) {
                scan_hop(now);
            }
            break;
        case MODE_TRACK:
            if (P.probe_hz && now >= P.next_probe_ms) {
                const uint8_t n = s_n_targets;
                if (n) {
                    send_probe(s_targets[P.probe_rr % n], P.track_ch);
                    P.probe_rr++;
                }
                P.next_probe_ms = now + 1000 / P.probe_hz;
            }
            if (now >= P.next_emit_ms) {
                track_emit(now);
                P.next_emit_ms += TRACK_EMIT_MS;
                if (P.next_emit_ms < now) {     /* nach Verzögerungen nicht nachholen */
                    P.next_emit_ms = now + TRACK_EMIT_MS;
                }
            }
            break;
        case MODE_IDLE:
            if (now >= P.next_status_ms) {
                out_line("{\"t\":\"st\",\"mode\":\"idle\",\"ms\":%" PRIu32 "}", (uint32_t)now);
                P.next_status_ms = now + IDLE_STATUS_MS;
            }
            break;
        default:
            break;
        }
    }
}

static void usb_rx_task(void *arg)
{
    char line[256];
    size_t n = 0;
    bool overflow = false;
    uint8_t buf[64];
    char err[96];
    apf_cmd_t cmd;

    for (;;) {
        int r = usb_serial_jtag_read_bytes(buf, sizeof(buf), pdMS_TO_TICKS(50));
        for (int i = 0; i < r; i++) {
            const char c = (char)buf[i];
            if (c == '\n' || c == '\r') {
                if (overflow) {
                    out_err("Zeile zu lang");
                } else if (n > 0) {
                    line[n] = '\0';
                    if (apf_cmd_parse(line, &cmd, err, sizeof(err))) {
                        if (xQueueSend(s_cmdq, &cmd, pdMS_TO_TICKS(200)) != pdTRUE) {
                            out_err("busy");
                        }
                    } else {
                        out_err(err);
                    }
                }
                n = 0;
                overflow = false;
            } else if (n < sizeof(line) - 1) {
                line[n++] = c;
            } else {
                overflow = true;
            }
        }
    }
}

/* ------------------------------------------------------------------ */
static void wifi_init(void)
{
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, WIFI_EVENT_FTM_REPORT,
                                                        &wifi_event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    esp_err_t e = esp_wifi_set_country_code("DE", false);
    if (e != ESP_OK) {
        ESP_LOGW(TAG, "Laendercode DE: 0x%x", (unsigned)e);
    }
    ESP_ERROR_CHECK(esp_wifi_start());
    esp_wifi_set_ps(WIFI_PS_NONE);
    e = esp_wifi_set_band_mode(WIFI_BAND_MODE_AUTO);
    if (e != ESP_OK) {
        ESP_LOGW(TAG, "Bandmodus AUTO: 0x%x", (unsigned)e);
    }
    ESP_ERROR_CHECK(esp_wifi_get_mac(WIFI_IF_STA, s_own_mac));

    wifi_promiscuous_filter_t filt = {
        .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT | WIFI_PROMIS_FILTER_MASK_DATA,
    };
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&filt));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_rx_cb(&promisc_cb));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true));
    tune(1);
}

void app_main(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    s_out_mtx = xSemaphoreCreateMutex();
    s_evt = xEventGroupCreate();
    s_rxq = xQueueCreate(RXQ_LEN, sizeof(rx_item_t));
    s_cmdq = xQueueCreate(CMDQ_LEN, sizeof(apf_cmd_t));
    configASSERT(s_out_mtx && s_evt && s_rxq && s_cmdq);

    usb_serial_jtag_driver_config_t ucfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    ucfg.tx_buffer_size = 4096;
    ucfg.rx_buffer_size = 512;
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&ucfg));

    wifi_init();

    xTaskCreate(proc_task, "apf_proc", 6144, NULL, 6, NULL);
    xTaskCreate(usb_rx_task, "apf_usb", 4096, NULL, 5, NULL);

    out_hello();
}
