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

package io.livekit.android.room.util

/**
 * Munges an answer to declare `stereo=1` on the Opus fmtp of every audio section whose counterpart
 * in [offer] advertises `sprop-stereo=1`.
 *
 * Per [RFC 7587 §7.1](https://datatracker.ietf.org/doc/html/rfc7587#section-7.1) `stereo` is the
 * *receiver's* preference: without it libwebrtc instantiates a mono Opus decoder and downmixes,
 * whatever the sender transmits. `sprop-stereo` states only what the sender emits, so it does not
 * carry the answerer's preference on its own. This mirrors `ensureAudioNackAndStereo()` in
 * client-sdk-js and `mungeOpusStereo(_:matchingOffer:)` in client-sdk-swift.
 *
 * Sections are matched by mid rather than by position, the Opus payload type is resolved in each
 * document separately, and fmtp parameters are compared whole, since `stereo=1` is a substring of
 * `sprop-stereo=1`. Every line the munge does not change is kept as it was.
 *
 * @return the answer with `stereo=1` added where the offer marks a section stereo; [answer]
 * itself when there is nothing to add.
 */
fun mungeOpusStereo(answer: String, offer: String): String {
    val stereoMids = parseSections(offer)
        .filter { section ->
            val payload = section.opusPayload()
            section.isAudio() && payload != null && "sprop-stereo=1" in section.fmtpParameters(payload)
        }
        .mapNotNull { it.mid() }
        .toSet()
    if (stereoMids.isEmpty()) return answer

    val newline = if ("\r\n" in answer) "\r\n" else "\n"
    val trailing = answer.endsWith(newline)
    val body = if (trailing) answer.substring(0, answer.length - newline.length) else answer
    val lines = body.split(newline).toMutableList()

    var changed = false
    // Walk the sections from the last so an inserted line never shifts one still to come.
    for (range in sectionRanges(lines).asReversed()) {
        val section = Section(lines.subList(range.first, range.last + 1).toList())
        val mid = section.mid() ?: continue
        if (!section.isAudio() || mid !in stereoMids) continue
        val payload = section.opusPayload() ?: continue

        val fmtpPrefix = "a=fmtp:$payload "
        val fmtpIndex = range.firstOrNull { lines[it].startsWith(fmtpPrefix) }
        if (fmtpIndex != null) {
            val parameters = lines[fmtpIndex].substring(fmtpPrefix.length)
            if ("stereo=1" in splitParameters(parameters)) continue
            lines[fmtpIndex] = if (parameters.isBlank()) "${fmtpPrefix}stereo=1" else "${lines[fmtpIndex]};stereo=1"
        } else {
            val rtpmapIndex = range.first { lines[it].startsWith("a=rtpmap:$payload ") }
            lines.add(rtpmapIndex + 1, "${fmtpPrefix}stereo=1")
        }
        changed = true
    }
    if (!changed) return answer
    return lines.joinToString(newline) + if (trailing) newline else ""
}

/** One `m=` section's lines, the `m=` line first. */
private class Section(val lines: List<String>) {
    fun isAudio(): Boolean = lines.first().startsWith("m=audio")

    fun mid(): String? = lines.firstNotNullOfOrNull { it.after("a=mid:")?.trim() }

    /** The payload type this section maps to Opus, if any. */
    fun opusPayload(): String? = lines.firstNotNullOfOrNull { line ->
        val rtpmap = line.after("a=rtpmap:") ?: return@firstNotNullOfOrNull null
        val parts = rtpmap.split(' ', limit = 2)
        if (parts.size == 2 && parts[1].trim().startsWith("opus/", ignoreCase = true)) parts[0].trim() else null
    }

    fun fmtpParameters(payload: String): List<String> =
        lines.firstNotNullOfOrNull { it.after("a=fmtp:$payload ") }?.let(::splitParameters) ?: emptyList()
}

private fun parseSections(sdp: String): List<Section> {
    val lines = sdp.split("\r\n", "\n")
    return sectionRanges(lines).map { range -> Section(lines.subList(range.first, range.last + 1)) }
}

/** The line ranges of each `m=` section, in order; the session part before the first is not one. */
private fun sectionRanges(lines: List<String>): List<IntRange> {
    val starts = lines.indices.filter { lines[it].startsWith("m=") }
    return starts.mapIndexed { index, start ->
        val end = if (index + 1 < starts.size) starts[index + 1] - 1 else lines.lastIndex
        start..end
    }
}

private fun splitParameters(parameters: String): List<String> = parameters.split(';').map { it.trim() }

private fun String.after(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null
