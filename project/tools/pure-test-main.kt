import com.stepper.app.data.StepMath
import com.stepper.app.location.LocationMath

fun main() {
    fun check(name: String, condition: Boolean) {
        require(condition) { "FAIL: $name" }
        println("PASS: $name")
    }

    check("initial counter does not import pre-install history", StepMath.counterDelta(null, 1234L) == 0L)
    check("positive counter delta", StepMath.counterDelta(10000L, 10543L) == 543L)
    check("counter reset is ignored", StepMath.counterDelta(10000L, 12L) == 0L)
    check("metric distance", kotlin.math.abs(StepMath.distanceMeters(1000L, 0.76) - 760.0) < 1e-9)
    check("active time lower bound", StepMath.activeSeconds(100L, 120000L) == 60L)
    check("active time cap", StepMath.activeSeconds(10000L, 3600000L) == 300L)
    check("goal progress clamp", StepMath.goalProgress(15000L, 10000L) == 1.0)
    check("same GPS point", LocationMath.distanceMeters(55.75, 37.62, 55.75, 37.62) == 0.0)
    check("equator 0.001 degree", LocationMath.distanceMeters(0.0, 0.0, 0.0, 0.001) in 110.0..112.5)
    check("dateline shortest arc", LocationMath.distanceMeters(0.0, 179.999, 0.0, -179.999) < 300.0)
    println("PURE JVM REGRESSION: ALL PASS")
}
