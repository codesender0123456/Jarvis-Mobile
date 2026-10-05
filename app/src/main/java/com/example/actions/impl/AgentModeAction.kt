package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.core.gemini.GeminiClient

class AgentModeAction(private val geminiClient: GeminiClient) : Action {

    override val name: String = "plan_and_execute"
    override val description: String = "Breaks a multi-step goal into a written plan. It returns the plan only; it does not run the steps."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "goal" to ParamDefinition("string", "The high-level user objective or complex multi-step request", required = true)
    )

    /**
     * This previously reported "Autonomous agent mode engaged" and "Execution plan formulated"
     * while running nothing at all, so the assistant implied it had carried out work it had
     * never touched. The planner now states plainly that nothing has been executed. Actually
     * chaining the steps needs an executor that dispatches each step through
     * [com.example.actions.ActionRegistry] and [com.example.actions.SafetyGuard] - that is a
     * separate, permission-sensitive feature, not something to fake.
     */
    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val goal = (args["goal"] as? String)?.trim() ?: return ActionResult("Goal required.", isError = true)

        if (goal.isEmpty()) return ActionResult("Goal required.", isError = true)

        val planPrompt = """Deconstruct this high-level objective into 2-3 precise execution steps:
Objective: $goal

Return a concise list of steps. Do not claim any step has been carried out."""
        val planResult = geminiClient.callOneShot(planPrompt)

        if (planResult.isError) {
            return ActionResult(
                spokenResult = "I couldn't build a plan for that. ${planResult.text}",
                isError = true
            )
        }

        return ActionResult(
            spokenResult = "Here's how I would break that down, Sir. I have not executed any of " +
                "it yet - tell me which step to start with.",
            cardData = mapOf(
                "goal" to goal,
                "plan" to planResult.text,
                "executed" to "false"
            )
        )
    }
}
