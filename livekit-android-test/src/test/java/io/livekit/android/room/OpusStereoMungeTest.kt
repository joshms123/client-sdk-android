/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import io.livekit.android.room.util.mungeOpusStereo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OpusStereoMungeTest {

    @Test
    fun addsStereoOnlyWhereTheOfferMarksSpropStereo() {
        val munged = mungeOpusStereo(answer(), offer(stereoMids = setOf("1")))

        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1", fmtpLines(munged, mid = "0").single())
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1", fmtpLines(munged, mid = "1").single())
    }

    @Test
    fun answerIsReturnedAsItIsWhenNothingIsMarkedStereo() {
        val answer = answer()
        assertSame(answer, mungeOpusStereo(answer, offer(stereoMids = emptySet())))
    }

    @Test
    fun spropStereoInTheAnswerIsNotStereo() {
        // `stereo=1` is a substring of `sprop-stereo=1`: only a whole parameter counts.
        val answer = answer(fmtp = "minptime=10;sprop-stereo=1")
        val munged = mungeOpusStereo(answer, offer(stereoMids = setOf("0")))
        assertEquals("a=fmtp:111 minptime=10;sprop-stereo=1;stereo=1", fmtpLines(munged, mid = "0").single())
    }

    @Test
    fun anAnswerThatAlreadyAsksForStereoIsLeftAlone() {
        val answer = answer(fmtp = "minptime=10;stereo=1")
        assertSame(answer, mungeOpusStereo(answer, offer(stereoMids = setOf("0", "1"))))
    }

    @Test
    fun sectionsMatchByMidAndEachDocumentHasItsOwnPayloadType() {
        val offer = offer(stereoMids = setOf("1"), opusPayload = 109)
        val munged = mungeOpusStereo(answer(), offer)
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1", fmtpLines(munged, mid = "1").single())
        assertEquals("a=fmtp:111 minptime=10;useinbandfec=1", fmtpLines(munged, mid = "0").single())
    }

    @Test
    fun aMissingFmtpLineIsAddedAfterTheRtpmap() {
        val answer = answer(fmtp = null)
        val munged = mungeOpusStereo(answer, offer(stereoMids = setOf("0")))
        val lines = sectionLines(munged, mid = "0")
        val rtpmap = lines.indexOf("a=rtpmap:111 opus/48000/2")
        assertEquals("a=fmtp:111 stereo=1", lines[rtpmap + 1])
    }

    @Test
    fun keepsTheLineEndingsAndEveryOtherLine() {
        val answer = answer()
        val munged = mungeOpusStereo(answer, offer(stereoMids = setOf("1")))
        assertEquals(answer.replace("a=fmtp:111 minptime=10;useinbandfec=1\r\na=mid:1", "X"), munged.replace("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1\r\na=mid:1", "X"))
        assertEquals(true, munged.endsWith("\r\n"))
        assertEquals(false, munged.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun videoAndNonOpusAudioAreNeverTouched() {
        val offer = offer(stereoMids = setOf("0", "1", "2"))
        val munged = mungeOpusStereo(answer(), offer)
        assertEquals(sectionLines(answer(), mid = "2"), sectionLines(munged, mid = "2"))
    }

    // --- fixtures -----------------------------------------------------------------

    /** Two Opus audio sections (mids 0 and 1, payload 111) and a video section (mid 2). */
    private fun answer(fmtp: String? = "minptime=10;useinbandfec=1"): String {
        fun audio(mid: String) = listOfNotNull(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 0",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:111 opus/48000/2",
            fmtp?.let { "a=fmtp:111 $it" },
            "a=mid:$mid",
            "a=rtpmap:0 PCMU/8000",
            "a=recvonly",
        )
        return (
            listOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1 2") +
                audio("0") + audio("1") +
                listOf("m=video 9 UDP/TLS/RTP/SAVPF 96", "a=rtpmap:96 VP8/90000", "a=fmtp:96 x-google-start-bitrate=800", "a=mid:2", "a=recvonly")
            ).joinToString("\r\n") + "\r\n"
    }

    private fun offer(stereoMids: Set<String>, opusPayload: Int = 111): String {
        fun audio(mid: String): List<String> {
            val sprop = if (mid in stereoMids) ";sprop-stereo=1" else ""
            return listOf(
                "m=audio 9 UDP/TLS/RTP/SAVPF $opusPayload 0",
                "a=mid:$mid",
                "a=rtpmap:$opusPayload opus/48000/2",
                "a=fmtp:$opusPayload minptime=10;useinbandfec=1$sprop",
                "a=sendonly",
            )
        }
        return (
            listOf("v=0", "o=- 3 4 IN IP4 0.0.0.0", "s=-", "t=0 0") +
                audio("0") + audio("1") +
                listOf("m=video 9 UDP/TLS/RTP/SAVPF 96", "a=mid:2", "a=rtpmap:96 VP8/90000", "a=fmtp:96 sprop-stereo=1", "a=sendonly")
            ).joinToString("\r\n") + "\r\n"
    }

    private fun sectionLines(sdp: String, mid: String): List<String> {
        val sections = mutableListOf<MutableList<String>>()
        for (line in sdp.split("\r\n")) {
            if (line.startsWith("m=")) sections.add(mutableListOf())
            sections.lastOrNull()?.add(line)
        }
        return sections.single { "a=mid:$mid" in it }
    }

    private fun fmtpLines(sdp: String, mid: String): List<String> =
        sectionLines(sdp, mid).filter { it.startsWith("a=fmtp:") }
}
