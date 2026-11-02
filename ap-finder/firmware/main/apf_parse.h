/*
 * AP-Finder – 802.11-Frame- und Information-Element-Parser.
 *
 * Reines C ohne ESP-IDF-Abhängigkeiten, damit es auch auf dem PC mit
 * Unit-Tests geprüft werden kann (siehe firmware/test).
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define APF_SSID_MAX      32
#define APF_NAME_MAX      32
#define APF_TXP_UNKNOWN   INT8_MIN

/* Flags in apf_bss_info_t.flags */
#define APF_F_FTM_RESP    0x01  /* Extended Capabilities Bit 70: FTM-Responder */
#define APF_F_PRIVACY     0x02  /* Capability-Info: Privacy (verschlüsselt) */
#define APF_F_RSN         0x04  /* RSN-Element vorhanden (WPA2/WPA3) */
#define APF_F_HIDDEN      0x08  /* SSID leer bzw. nur Nullbytes */
#define APF_F_HT          0x10  /* 802.11n */
#define APF_F_VHT         0x20  /* 802.11ac */
#define APF_F_HE          0x40  /* 802.11ax */

typedef enum {
    APF_FRAME_OTHER = 0,
    APF_FRAME_BEACON,
    APF_FRAME_PROBE_RESP,
    APF_FRAME_MGMT_OTHER,   /* Action, Auth, Assoc-Resp … */
    APF_FRAME_DATA,         /* Datenframe (inkl. Null/QoS-Null) */
} apf_frame_kind_t;

typedef struct {
    apf_frame_kind_t kind;
    const uint8_t *ta;       /* Sender (Addr2) oder NULL (z. B. ACK/CTS) */
    const uint8_t *bssid;    /* BSSID laut Header oder NULL */
    const uint8_t *body;     /* Management-Body nach dem Header */
    uint16_t body_len;
} apf_frame_t;

typedef struct {
    uint8_t  bssid[6];
    uint8_t  ssid_len;
    char     ssid[APF_SSID_MAX + 1];   /* Rohbytes, NUL-terminiert */
    char     name[APF_NAME_MAX + 1];   /* AP-Name aus Hersteller-IE, "" = keiner */
    uint8_t  channel;                  /* aus DS-Parameter/HT-Operation, 0 = unbekannt */
    int8_t   tx_power;                 /* dBm aus TPC-Report/Cisco DTPC, sonst APF_TXP_UNKNOWN */
    uint8_t  flags;                    /* APF_F_* */
    uint16_t beacon_int;               /* in TU (1 TU = 1,024 ms) */
} apf_bss_info_t;

/*
 * Zerlegt den 802.11-Header. frame/len ohne FCS.
 * Gibt false zurück, wenn der Frame zu kurz oder kein auswertbarer Typ ist.
 */
bool apf_parse_frame(const uint8_t *frame, uint16_t len, apf_frame_t *out);

/*
 * Wertet Beacon/Probe-Response aus (feste Felder + Information Elements).
 * Liefert false, wenn f kein Beacon/Probe-Response ist.
 */
bool apf_parse_bss_info(const apf_frame_t *f, apf_bss_info_t *info);

/* Hilfsfunktion: Kanal → Band (2 = 2,4 GHz, 5 = 5 GHz, 0 = unbekannt) */
int apf_channel_band(uint8_t channel);

#ifdef __cplusplus
}
#endif
