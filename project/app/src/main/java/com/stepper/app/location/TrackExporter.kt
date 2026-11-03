package com.stepper.app.location

import java.util.Locale

object TrackExporter {
    fun toGpx(session: LocationDb.TrackSession, points: List<LocationDb.TrackPoint>): String {
        val b = StringBuilder()
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        b.append("<gpx version=\"1.1\" creator=\"Шагомер\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        b.append("<trk><name>Шагомер — ").append(escape(session.activity)).append("</name><trkseg>\n")
        points.forEach { p ->
            b.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time>", p.latitude, p.longitude, p.recordedUtc))
            b.append("<extensions><accuracy>").append(p.accuracyMeters).append("</accuracy>")
            p.speedMps?.let { b.append("<speed>").append(it).append("</speed>") }
            b.append("</extensions></trkpt>\n")
        }
        b.append("</trkseg></trk></gpx>\n")
        return b.toString()
    }

    fun googleMapsUrl(session: LocationDb.TrackSession, points: List<LocationDb.TrackPoint>): String? {
        if (points.size < 2) return null
        val start = points.first()
        val end = points.last()
        return String.format(
            Locale.US,
            "https://www.google.com/maps/dir/?api=1&origin=%.7f,%.7f&destination=%.7f,%.7f&travelmode=walking",
            start.latitude, start.longitude, end.latitude, end.longitude
        )
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
