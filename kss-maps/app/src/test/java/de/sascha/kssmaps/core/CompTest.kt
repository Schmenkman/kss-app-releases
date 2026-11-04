package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** COMP-Seiten und -Antworten auswerten. Alles kuenstlich nachgebaut – keine echten Daten. */
class CompTest {

    @Test
    fun loginFormWithHiddenFields() {
        val html = """
            <html><body><form class="x" id="form" method="post" action="/de/login.form?a=1&amp;b=2">
              <input type="hidden" name="t:formdata" value="H4sIAAAAAAAA/abc+def=="/>
              <input value="" type="hidden" name="lastPage">
              <input type="text" name="usernameField" value="">
              <input type="password" name="passwordField">
            </form><form id="other"><input type="hidden" name="no" value="x"></form></body></html>
        """.trimIndent()
        val f = Comp.loginForm(html)!!
        assertEquals("/de/login.form?a=1&b=2", f.action)
        assertEquals(mapOf("t:formdata" to "H4sIAAAAAAAA/abc+def==", "lastPage" to ""), f.hidden)
        assertNull(Comp.loginForm("<html>keine Anmeldung</html>"))
    }

    @Test
    fun userIdFromSchedulerPage() {
        val html = """<script>var cfg = {"locale":"de","userId":1234,"amq":{"queue":"amq/queue/x","p":"nicht-merken"}};</script>"""
        assertEquals("1234", Comp.userId(html))
        assertEquals("77", Comp.userId("""{"userId": "77"}"""))
        assertNull(Comp.userId("<html></html>"))
        assertTrue(Comp.isLoginPage("https://example.invalid/de/login?x"))
    }

    @Test
    fun techniciansWithPhones() {
        val json = """[
            {"id":101,"name":"Test Techniker A","allPhoneNumbers":"0171 1111111, +49 89 2222222","dmUser":null},
            {"id":102,"name":"Test Techniker B","allPhoneNumbers":null},
            {"id":101,"name":"doppelt"},
            {"id":null,"name":"ohne Nummer"}
        ]"""
        val t = Comp.technicians(json)
        assertEquals(listOf(101, 102), t.map { it.id })
        assertEquals("Test Techniker A", t[0].name)
        assertEquals(listOf("01711111111", "0892222222"), t[0].phones)
        assertTrue(t[1].phones.isEmpty())
    }

    @Test
    fun ordersOnlySortedWithAddressAndNote() {
        val json = """[
            {"type":"block","id":1,"start_date":"2026-11-05T07:00:00+01:00","end_date":"2026-11-05T08:00:00+01:00","section_id":[101]},
            {"type":"order","id":22000002,"label":"009-0000002/26","start_date":"2026-11-05T10:00:00+01:00","end_date":"2026-11-05T11:30:00+01:00",
             "section_id":[101],"text":"Termin mit Herr Test 0171 3333333 bestätigt.<br>Vorher anrufen &amp; klingeln",
             "additionalData":{"street":"Teststraße 2","zip":"85354","city":"Freising","customerName":"Testkunde 2","condition":"terminiert","terminaddition":"gekürzt"}},
            {"type":"order","id":22000001,"label":"009-0000001/26","start_date":"2026-11-05T07:00:00.000Z","end_date":"2026-11-05T09:00:00.000Z",
             "section_id":[101,102],"text":null,
             "additionalData":{"street":"Teststraße 1","zip":"85354","city":"Freising","customerName":null,"terminaddition":"Kunde öffnet selbst"}}
        ]"""
        val a = Comp.appointments(json)
        assertEquals(listOf("22000001", "22000002"), a.map { it.id })
        val first = a[0]
        assertEquals("Teststraße 1, 85354 Freising", first.address)
        assertEquals("", first.customerName)
        assertEquals("Kunde öffnet selbst", first.note)
        assertEquals(listOf(101, 102), first.technicianIds)
        assertEquals("08:00–10:00", Appointments.timeRange(first))
        val second = a[1]
        assertEquals("Termin mit Herr Test 0171 3333333 bestätigt.\nVorher anrufen & klingeln", second.note)
        assertEquals("terminiert", second.condition)
        assertEquals("11:30", Appointments.timeRange(second).substringAfter('–'))
        // Fuer Techniker 102 nur der gemeinsame Termin
        assertEquals(listOf("22000001"), Appointments.forTechnician(a, 102).map { it.id })
        assertTrue(Comp.appointments("[]").isEmpty())
        assertEquals(1, Comp.appointments("""{"data":[${json.substringAfter("},").substringBefore(",\n            {\"type\":\"order\",\"id\":22000001")}]}""").size)
    }
}
