package com.spop.poverlay.workout

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class TcxExporterTest {
    @Test fun `indoor ride exports real time series without GPS and handles missing metrics`() {
        val start = 1_700_000_000_000L
        val workout = Workout("test", start, endedAt = start + 2000, distanceMeters = 5.0)
        val samples = listOf(
            WorkoutSample("test", start, 200f, 80f, 35f, 5.0, 135, 0.0),
            WorkoutSample("test", start + 2000, null, null, null, null, null, 5.0),
        )
        val xml = TcxExporter.export(workout, samples)
        val schemaFactory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI)
        val schema = schemaFactory.newSchema(arrayOf(
            javax.xml.transform.stream.StreamSource(javaClass.getResourceAsStream("/garmin/TrainingCenterDatabasev2.xsd")),
            javax.xml.transform.stream.StreamSource(javaClass.getResourceAsStream("/garmin/ActivityExtensionv2.xsd")),
        ))
        schema.newValidator().validate(javax.xml.transform.stream.StreamSource(StringReader(xml)))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals(2, document.getElementsByTagName("Trackpoint").length)
        assertEquals(0, document.getElementsByTagName("Position").length)
        assertEquals("200", document.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/ActivityExtension/v2", "Watts").item(0).textContent)
        assertEquals(1, document.getElementsByTagName("HeartRateBpm").length)
        assertEquals("2.0", document.getElementsByTagName("TotalTimeSeconds").item(0).textContent)
        assertEquals(xml, TcxExporter.export(workout, samples))
    }
    @Test(expected = IllegalArgumentException::class) fun `unfinished recording cannot be exported`() {
        TcxExporter.export(Workout("test", 1000), emptyList())
    }
    @Test fun `sensor freshness never revives invalid or old readings`() {
        assertEquals(200f, WorkoutRecorder.Reading(200f, 1000).fresh(11_000))
        assertNull(WorkoutRecorder.Reading(200f, 1000).fresh(11_001))
        assertNull(WorkoutRecorder.Reading(Float.NaN, 1000).fresh(1000))
        assertNull(WorkoutRecorder.Reading(-1f, 1000).fresh(1000))
        assertNull(WorkoutRecorder.Reading(200f, 1000).fresh(999))
    }
}
