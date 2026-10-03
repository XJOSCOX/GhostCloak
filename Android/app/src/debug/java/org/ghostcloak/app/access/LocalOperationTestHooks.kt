package org.ghostcloak.app.access

/** In-process instrumentation only. No exported component, gesture or UI, absent from release.
 * No reset hook on the real Application: disposable fixture gates are restored by test harnesses. */
internal object LocalOperationTestHooks {
    fun arm(gate: LocalOperationGate) = gate.armForSimulation()
}
