/*
 * AP-Finder – Protokoll-Hilfen: Befehlszeilen parsen (Handy → ESP) und
 * JSON-Zeilen bauen (ESP → Handy). Reines C, auf dem PC testbar.
 *
 * Befehle (eine Zeile, Groß-/Kleinschreibung egal), siehe docs/PROTOKOLL.md:
 *   HELLO | PING | STOP
 *   SCAN [dwell_ms] [bands]                 bands: 2, 5 oder 25
 *   TRACK <ch> <probe_hz> <bssid[,bssid…]> [ssid_hex]
 *   FTM <ch> <bssid> [frames]
 *   DATA <0|1>                              Datenframes des APs mitzählen
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define APF_MAX_TARGETS 8

typedef enum {
    APF_CMD_NONE = 0,
    APF_CMD_HELLO,
    APF_CMD_PING,
    APF_CMD_STOP,
    APF_CMD_SCAN,
    APF_CMD_TRACK,
    APF_CMD_FTM,
    APF_CMD_DATA,
} apf_cmd_type_t;

#define APF_BAND_2G 0x01
#define APF_BAND_5G 0x02

typedef struct {
    apf_cmd_type_t type;
    uint16_t dwell_ms;                       /* SCAN */
    uint8_t  bands;                          /* SCAN: APF_BAND_* */
    uint8_t  channel;                        /* TRACK, FTM */
    uint8_t  probe_hz;                       /* TRACK: 0 = nur passiv */
    uint8_t  n_bssid;                        /* TRACK: 1…8, FTM: 1 */
    uint8_t  bssid[APF_MAX_TARGETS][6];
    uint8_t  ssid[32];                       /* TRACK: optional für Probe Requests */
    uint8_t  ssid_len;
    uint8_t  frames;                         /* FTM: 16, 24, 32 oder 64 */
    bool     flag;                           /* DATA */
} apf_cmd_t;

/* Parst eine Befehlszeile. Bei Fehler false und Klartext in err. */
bool apf_cmd_parse(const char *line, apf_cmd_t *out, char *err, size_t errlen);

/* "aa:bb:cc:dd:ee:ff" (auch '-' oder ohne Trenner). */
bool apf_parse_mac(const char *s, size_t n, uint8_t mac[6]);

/* MAC als "aa:bb:cc:dd:ee:ff" (out mind. 18 Byte). */
void apf_mac_to_str(const uint8_t mac[6], char *out);

/*
 * Schreibt src (len Bytes) als JSON-String-Inhalt (ohne Anführungszeichen)
 * nach dst. Escaped " \ und Steuerzeichen; Bytes >= 0x80 werden unverändert
 * übernommen (UTF-8-SSIDs). Gibt die geschriebene Länge zurück (ohne NUL);
 * schneidet bei Platzmangel sauber ab.
 */
size_t apf_json_escape(char *dst, size_t cap, const uint8_t *src, size_t len);

/* Kanallisten für den Scan (EU/DE). */
size_t apf_scan_channels(uint8_t bands, uint8_t *out, size_t cap);

#ifdef __cplusplus
}
#endif
