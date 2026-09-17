package com.cairn.reader.domain.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parser correctness for every caption format Cairn ingests. Robolectric is used because the JSON
 * path touches org.json (Android's bundled implementation), which the stubbed JVM one returns
 * defaults for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptionParsersTest {

    @Test fun parsesVttWithHeaderAndSettings() {
        val vtt = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:04.000 align:start position:0%
            Hello <c>world</c>

            00:01:05.500 --> 00:01:07.000
            second line
            wraps
        """.trimIndent()
        val cues = CaptionParsers.parseVtt(vtt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
        assertEquals(65_500L, cues[1].startMs)
        assertEquals("second line\nwraps", cues[1].text)
    }

    @Test fun parsesSrtWithCommaMillis() {
        val srt = """
            1
            00:00:00,000 --> 00:00:02,500
            First

            2
            01:02:03,250 --> 01:02:05,000
            Later on
        """.trimIndent()
        val cues = CaptionParsers.parseSrt(srt)
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals(3_723_250L, cues[1].startMs) // 1h2m3.25s
        assertEquals("Later on", cues[1].text)
    }

    @Test fun parsesYouTubeTimedText() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <transcript>
            <text start="0" dur="2.5">Hey &amp; welcome</text>
            <text start="2.5" dur="3">it&#39;s a test</text>
            </transcript>
        """.trimIndent()
        val cues = CaptionParsers.parseTimedTextXml(xml)
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals("Hey & welcome", cues[0].text)
        assertEquals("it's a test", cues[1].text)
        assertEquals(5500L, cues[1].endMs)
    }

    @Test fun parsesPodcast20JsonSegments() {
        val json = """
            {"version":"1.0.0","segments":[
              {"speaker":"Host","startTime":0.8,"endTime":5.4,"body":"Welcome back"},
              {"startTime":5.4,"endTime":9.0,"body":"to the show"}
            ]}
        """.trimIndent()
        val cues = CaptionParsers.parseJsonTranscript(json)
        assertEquals(2, cues.size)
        assertEquals(800L, cues[0].startMs)
        assertEquals(5400L, cues[0].endMs)
        assertEquals("Welcome back", cues[0].text)
        assertEquals("to the show", cues[1].text)
    }

    @Test fun dispatcherPicksFormatFromHintAndSniff() {
        assertTrue(CaptionParsers.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhi").isNotEmpty())
        assertTrue(CaptionParsers.parse("<transcript><text start=\"0\" dur=\"1\">hi</text></transcript>", "text/xml").isNotEmpty())
        assertTrue(CaptionParsers.parse("{\"segments\":[{\"startTime\":0,\"endTime\":1,\"body\":\"hi\"}]}", "application/json").isNotEmpty())
        assertEquals(0, CaptionParsers.parse("not a transcript at all").size)
    }

    @Test fun parsesYouTubeJson3() {
        val json = """
            {"wireMagic":"pb3","events":[
              {"tStartMs":0,"dDurationMs":1200,"segs":[{"utf8":"Hello "},{"utf8":"there"}]},
              {"tStartMs":1200,"dDurationMs":800,"segs":[{"utf8":"world"}]},
              {"tStartMs":2000,"dDurationMs":500}
            ]}
        """.trimIndent()
        val cues = CaptionParsers.parseJson3(json)
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(1200L, cues[0].endMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals("world", cues[1].text)
    }

    @Test fun dispatcherRoutesJson3ByEventsKey() {
        val cues = CaptionParsers.parse("{\"events\":[{\"tStartMs\":0,\"dDurationMs\":900,\"segs\":[{\"utf8\":\"hi\"}]}]}")
        assertEquals(1, cues.size)
        assertEquals("hi", cues[0].text)
    }

    @Test fun timestampParsingHandlesAllShapes() {
        assertEquals(1000L, CaptionParsers.parseTimestamp("00:00:01.000"))
        assertEquals(65_500L, CaptionParsers.parseTimestamp("01:05.500"))
        assertEquals(2500L, CaptionParsers.parseTimestamp("00:00:02,500"))
        assertEquals(3_723_250L, CaptionParsers.parseTimestamp("01:02:03.25"))
        assertEquals(null, CaptionParsers.parseTimestamp("garbage"))
    }
}
