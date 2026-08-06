#!/usr/bin/env python3
"""Offline-Tests fuer die Erkennungslogik -- laufen ohne Netz.

    python3 test_watcher.py
"""

import unittest

from watcher import diff_excerpt, find_dates, html_to_text, scan_for_signals, signal_key

TERMS = {
    "prereg": ["pre-registration", "vorregistrierung"],
    "sale": ["ticket sale", "global journey"],
    "year": ["2027"],
}


class TestTextExtraction(unittest.TestCase):
    def test_strips_scripts_and_styles(self):
        html = """
        <html><head><style>.a{color:red}</style></head>
        <body><script>var x = 'pre-registration 2027';</script>
        <h1>Tickets</h1><p>Pre-registration opens on 8 December 2026.</p></body></html>
        """
        text = html_to_text(html)
        self.assertIn("Pre-registration opens on 8 December 2026.", text)
        self.assertNotIn("var x", text)
        self.assertNotIn("color:red", text)

    def test_drops_cache_busting_hashes(self):
        first = html_to_text("<p>Tickets</p><p>a3f9c2b1d4e6f8a0b2c4d6e8</p>")
        second = html_to_text("<p>Tickets</p><p>ff11ee22dd33cc44bb55aa66</p>")
        self.assertEqual(first, second)


class TestDateDetection(unittest.TestCase):
    def test_finds_common_formats(self):
        hits = find_dates("opens on 8 December 2026 at 15:00 CET")
        self.assertIn("8 December 2026", hits)
        self.assertIn("15:00 CET", hits)
        self.assertTrue(find_dates("Verkauf am 30.01.2027"))
        self.assertTrue(find_dates("December 8, 2026"))

    def test_ignores_plain_text(self):
        self.assertEqual(find_dates("Tickets are sold out"), [])


class TestSignalScan(unittest.TestCase):
    def test_detects_prereg_announcement(self):
        text = "Home\nPre-registration for Tomorrowland 2027 opens on 8 December 2026 at 15:00 CET.\nFooter"
        signals = scan_for_signals(text, TERMS)
        self.assertEqual(len(signals), 1)
        self.assertEqual(signals[0]["kind"], "prereg")
        self.assertTrue(signals[0]["has_year"])
        self.assertIn("8 December 2026", signals[0]["dates"])

    def test_detects_sale_line_without_year_if_dated(self):
        text = "Global Journey packages go on sale 15 January at 17:00 CET."
        self.assertEqual(len(scan_for_signals(text, TERMS)), 1)

    def test_ignores_generic_mentions(self):
        text = "Read our ticket sale FAQ\nPre-registration is mandatory."
        self.assertEqual(scan_for_signals(text, TERMS), [])

    def test_ignores_long_navigation_blobs(self):
        text = "pre-registration 2027 " + "nav link " * 60
        self.assertEqual(scan_for_signals(text, TERMS), [])

    def test_key_is_stable_across_whitespace_and_case(self):
        a = {"kind": "prereg", "line": "Pre-registration  opens 8 December 2026"}
        b = {"kind": "prereg", "line": "pre-registration opens 8 December 2026"}
        self.assertEqual(signal_key("Tickets", a), signal_key("Tickets", b))

    def test_key_differs_per_source(self):
        sig = {"kind": "prereg", "line": "Pre-registration opens 8 December 2026"}
        self.assertNotEqual(signal_key("Tickets", sig), signal_key("Presse", sig))


class TestDiff(unittest.TestCase):
    def test_reports_added_lines(self):
        excerpt = diff_excerpt("alte Zeile hier drin", "alte Zeile hier drin\nneue wichtige Zeile")
        self.assertIn("neue wichtige Zeile", excerpt)

    def test_handles_first_run(self):
        self.assertIn("neue Zeile mit Inhalt", diff_excerpt("", "neue Zeile mit Inhalt"))

    def test_no_new_text(self):
        self.assertIn("kein neuer Text", diff_excerpt("a b c", "a b c"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
