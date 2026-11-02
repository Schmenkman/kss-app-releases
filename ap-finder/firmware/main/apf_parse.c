/*
 * AP-Finder – 802.11-Frame- und Information-Element-Parser.
 *
 * Formate der herstellerspezifischen AP-Namen nach dem Wireshark-Dissector
 * epan/dissectors/packet-ieee80211.c (Cisco/Meraki, Aruba, Huawei, Mist,
 * Ubiquiti, Ruckus, Extreme, Fortinet, Arista, Alcatel-Lucent, …).
 */
#include "apf_parse.h"

#include <string.h>

#define FC_TYPE(fc0)     (((fc0) >> 2) & 0x3)
#define FC_SUBTYPE(fc0)  (((fc0) >> 4) & 0xF)

/* Priorität der Namensquellen: höhere gewinnt */
#define NAME_PRIO_CCX1    1   /* Cisco IE 133 (16 Zeichen, nur mit CCX) */
#define NAME_PRIO_VENDOR  2   /* Hersteller-IE */
#define NAME_PRIO_CISCOV2 3   /* Cisco/Meraki „AP name v2“ (bis 32 Zeichen) */

bool apf_parse_frame(const uint8_t *frame, uint16_t len, apf_frame_t *out)
{
    if (!frame || !out) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    if (len < 24) {
        return false;
    }
    const uint8_t fc0 = frame[0];
    const uint8_t fc1 = frame[1];
    if ((fc0 & 0x3) != 0) {            /* Protokollversion muss 0 sein */
        return false;
    }
    const uint8_t type = FC_TYPE(fc0);
    const uint8_t sub = FC_SUBTYPE(fc0);

    if (type == 0) {                    /* Management */
        uint16_t hdr = 24;
        if (fc1 & 0x80) {               /* +HTC: HT-Control-Feld (4 Byte) */
            hdr += 4;
        }
        if (len < hdr) {
            return false;
        }
        out->ta = frame + 10;
        out->bssid = frame + 16;
        out->body = frame + hdr;
        out->body_len = (uint16_t)(len - hdr);
        if (sub == 8) {
            out->kind = APF_FRAME_BEACON;
        } else if (sub == 5) {
            out->kind = APF_FRAME_PROBE_RESP;
        } else {
            out->kind = APF_FRAME_MGMT_OTHER;
        }
        return true;
    }

    if (type == 2) {                    /* Daten */
        const bool to_ds = (fc1 & 0x01) != 0;
        const bool from_ds = (fc1 & 0x02) != 0;
        out->kind = APF_FRAME_DATA;
        out->ta = frame + 10;           /* Addr2 = Sender */
        if (!to_ds && !from_ds) {
            out->bssid = frame + 16;    /* IBSS: Addr3 */
        } else if (!to_ds && from_ds) {
            out->bssid = frame + 10;    /* AP → Client: Addr2 */
        } else if (to_ds && !from_ds) {
            out->bssid = frame + 4;     /* Client → AP: Addr1 */
        } else {
            out->bssid = NULL;          /* WDS/Mesh */
        }
        return true;
    }

    return false;                       /* Control/Extension: nicht genutzt */
}

int apf_channel_band(uint8_t channel)
{
    if (channel >= 1 && channel <= 14) {
        return 2;
    }
    if (channel >= 32 && channel <= 177) {
        return 5;
    }
    return 0;
}

/* Kopiert einen ASCII-Namen: stoppt an NUL, ersetzt Steuer-/Nicht-ASCII-Zeichen
 * durch '?', schneidet Leerzeichen an Anfang und Ende ab. */
static bool copy_name(char *dst, const uint8_t *src, size_t n)
{
    size_t start = 0;
    while (start < n && src[start] == ' ') {
        start++;
    }
    size_t o = 0;
    for (size_t i = start; i < n && o < APF_NAME_MAX; i++) {
        uint8_t c = src[i];
        if (c == 0) {
            break;
        }
        dst[o++] = (c >= 0x20 && c < 0x7F) ? (char)c : '?';
    }
    while (o > 0 && dst[o - 1] == ' ') {
        o--;
    }
    dst[o] = '\0';
    return o > 0;
}

static void set_name(apf_bss_info_t *info, const uint8_t *src, size_t n, int prio, int *cur_prio)
{
    if (prio <= *cur_prio) {
        return;
    }
    char tmp[APF_NAME_MAX + 1];
    if (copy_name(tmp, src, n)) {
        memcpy(info->name, tmp, sizeof(tmp));
        *cur_prio = prio;
    }
}

/* Häufigstes Format: <OUI><Typ><Name…> */
static void vendor_common(const uint8_t *r, size_t rl, uint8_t name_type,
                          apf_bss_info_t *info, int *prio)
{
    if (rl > 1 && r[0] == name_type) {
        set_name(info, r + 1, rl - 1, NAME_PRIO_VENDOR, prio);
    }
}

static void parse_vendor(const uint8_t *v, uint8_t l, apf_bss_info_t *info,
                         int *prio, int8_t *dtpc)
{
    if (l < 4) {
        return;
    }
    const uint32_t oui = ((uint32_t)v[0] << 16) | ((uint32_t)v[1] << 8) | v[2];
    const uint8_t *r = v + 3;
    const size_t rl = (size_t)l - 3;

    switch (oui) {
    case 0x004096:      /* Cisco Aironet – auch Meraki */
        if (r[0] == 0x2F && rl > 1) {                 /* AP name v2 */
            set_name(info, r + 1, rl - 1, NAME_PRIO_CISCOV2, prio);
        } else if (r[0] == 0x00 && rl >= 2) {         /* DTPC: Sendeleistung */
            *dtpc = (int8_t)r[1];
        }
        break;
    case 0x000B86:      /* Aruba: Typ, Subtyp 3 = Name, 1 Byte, Name */
        if (rl > 3 && r[1] == 3) {
            set_name(info, r + 3, rl - 3, NAME_PRIO_VENDOR, prio);
        }
        break;
    case 0x00E0FC:      /* Huawei: Typ, Subtyp 1 = Name, 1 Byte, Name */
        if (rl > 3 && r[1] == 1) {
            set_name(info, r + 3, rl - 3, NAME_PRIO_VENDOR, prio);
        }
        break;
    case 0x5C5B35: vendor_common(r, rl, 1, info, prio); break;  /* Mist */
    case 0x00156D: vendor_common(r, rl, 1, info, prio); break;  /* Ubiquiti */
    case 0x848094: vendor_common(r, rl, 0, info, prio); break;  /* Meter */
    case 0x48D017: vendor_common(r, rl, 2, info, prio); break;  /* Telecom Infra Project */
    case 0x001392: vendor_common(r, rl, 3, info, prio); break;  /* Ruckus */
    case 0xDC0856: vendor_common(r, rl, 1, info, prio); break;  /* Alcatel-Lucent */
    case 0x001174: vendor_common(r, rl, 6, info, prio); break;  /* Arista (Mojo) */
    case 0x3CB9A6: vendor_common(r, rl, 1, info, prio); break;  /* Belden */
    case 0x00A0F8:      /* Extreme/WiNG: Subtyp 1, 7 Byte, Länge, Name */
        if (rl >= 9 && r[0] == 1) {
            size_t nl = r[8];
            if (9 + nl <= rl) {
                set_name(info, r + 9, nl, NAME_PRIO_VENDOR, prio);
            }
        }
        break;
    case 0x00090F:      /* Fortinet: Subtyp (LE16) 10 = SYSTEM, dann TLVs */
        if (rl >= 2 && (r[0] | (r[1] << 8)) == 10) {
            size_t o = 2;
            while (o + 2 <= rl) {
                uint8_t t = r[o];
                size_t tl = r[o + 1];
                o += 2;
                if (o + tl > rl) {
                    break;
                }
                if (t == 1) {
                    set_name(info, r + o, tl, NAME_PRIO_VENDOR, prio);
                }
                o += tl;
            }
        }
        break;
    default:
        break;
    }
}

bool apf_parse_bss_info(const apf_frame_t *f, apf_bss_info_t *info)
{
    if (!f || !info) {
        return false;
    }
    if (f->kind != APF_FRAME_BEACON && f->kind != APF_FRAME_PROBE_RESP) {
        return false;
    }
    memset(info, 0, sizeof(*info));
    info->tx_power = APF_TXP_UNKNOWN;
    if (f->bssid) {
        memcpy(info->bssid, f->bssid, 6);
    }
    if (!f->body || f->body_len < 12) {
        return false;
    }

    const uint8_t *b = f->body;
    info->beacon_int = (uint16_t)(b[8] | (b[9] << 8));
    const uint16_t cap = (uint16_t)(b[10] | (b[11] << 8));
    if (cap & 0x0010) {
        info->flags |= APF_F_PRIVACY;
    }

    bool ssid_seen = false;
    int name_prio = 0;
    uint8_t ds_chan = 0;
    uint8_t ht_chan = 0;
    int8_t dtpc = APF_TXP_UNKNOWN;

    size_t off = 12;
    while (off + 2 <= f->body_len) {
        const uint8_t id = b[off];
        const uint8_t l = b[off + 1];
        const uint8_t *v = b + off + 2;
        if (off + 2 + (size_t)l > f->body_len) {
            break;                       /* abgeschnittenes Element */
        }
        switch (id) {
        case 0:     /* SSID */
            if (!ssid_seen) {
                ssid_seen = true;
                uint8_t n = l > APF_SSID_MAX ? APF_SSID_MAX : l;
                bool all_zero = true;
                for (uint8_t i = 0; i < n; i++) {
                    if (v[i] != 0) {
                        all_zero = false;
                        break;
                    }
                }
                if (n == 0 || all_zero) {
                    info->flags |= APF_F_HIDDEN;
                    info->ssid_len = 0;
                    info->ssid[0] = '\0';
                } else {
                    memcpy(info->ssid, v, n);
                    info->ssid[n] = '\0';
                    info->ssid_len = n;
                }
            }
            break;
        case 3:     /* DS Parameter Set */
            if (l >= 1) {
                ds_chan = v[0];
            }
            break;
        case 35:    /* TPC Report: Sendeleistung in dBm */
            if (l >= 1) {
                info->tx_power = (int8_t)v[0];
            }
            break;
        case 45:    /* HT Capabilities */
            info->flags |= APF_F_HT;
            break;
        case 48:    /* RSN */
            info->flags |= APF_F_RSN;
            break;
        case 61:    /* HT Operation: Primärkanal */
            if (l >= 1) {
                ht_chan = v[0];
            }
            break;
        case 127:   /* Extended Capabilities: Bit 70 = FTM-Responder */
            if (l >= 9 && (v[8] & 0x40)) {
                info->flags |= APF_F_FTM_RESP;
            }
            break;
        case 133:   /* Cisco CCX1 CKIP + Device Name: Name ab Offset 10, 16 Byte */
            if (l >= 26) {
                set_name(info, v + 10, 16, NAME_PRIO_CCX1, &name_prio);
            }
            break;
        case 191:   /* VHT Capabilities */
            info->flags |= APF_F_VHT;
            break;
        case 221:   /* Vendor Specific */
            parse_vendor(v, l, info, &name_prio, &dtpc);
            break;
        case 255:   /* Element-ID-Erweiterung, 35 = HE Capabilities */
            if (l >= 1 && v[0] == 35) {
                info->flags |= APF_F_HE;
            }
            break;
        default:
            break;
        }
        off += 2 + (size_t)l;
    }

    if (!ssid_seen) {
        info->flags |= APF_F_HIDDEN;
    }
    info->channel = ds_chan ? ds_chan : ht_chan;
    if (info->tx_power == APF_TXP_UNKNOWN && dtpc != APF_TXP_UNKNOWN) {
        info->tx_power = dtpc;
    }
    return true;
}
