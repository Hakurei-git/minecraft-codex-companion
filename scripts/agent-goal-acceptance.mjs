export const TERMINAL_GOAL_STATUSES = new Set(["succeeded", "failed", "cancelled"]);

export function taskIdsFromPlan(plan) {
  return new Set((plan?.nodes ?? [])
    .map((node) => node?.checkpoint?.taskId)
    .filter((id) => typeof id === "string" && id.length > 0));
}

export function findNewAgentGoal(goals, previousGoalIds, companionId, objective) {
  return (goals ?? []).find((candidate) => (
    candidate?.companionId === companionId
    && typeof candidate.id === "string"
    && !previousGoalIds.has(candidate.id)
    && candidate.spec?.source === "t-chat"
    && (objective === undefined || candidate.spec?.objective === objective)
  )) ?? null;
}

export function findCraftNode(plan, expectedSpec) {
  return (plan?.nodes ?? []).find((node) => (
    node?.action?.kind === "task"
    && node.action.spec?.kind === "craft"
    && node.action.spec?.itemId === expectedSpec.itemId
    && node.action.spec?.count === expectedSpec.count
    && (expectedSpec.deliverTo === undefined || node.action.spec?.deliverTo === expectedSpec.deliverTo)
  )) ?? null;
}

export function validateAgentGoalPlan(goal, plan, expectedSpec) {
  if (!goal?.id || goal.spec?.source !== "t-chat") throw new Error("The T-chat action did not create a local Agent goal");
  if (plan?.goalId !== goal.id || !Array.isArray(plan.nodes) || plan.nodes.length < 2) {
    throw new Error("The Agent goal has no usable WorkGraph");
  }
  const finalNode = findCraftNode(plan, expectedSpec);
  if (!finalNode) throw new Error("The Agent WorkGraph has no final requested craft/delivery node");
  return finalNode;
}

export function assertGoalTaskCoverage(plan, tasksById) {
  const taskIds = taskIdsFromPlan(plan);
  for (const taskId of taskIds) {
    if (!tasksById.has(taskId)) throw new Error(`WorkGraph task checkpoint ${taskId} is missing from the task journal`);
  }
  return taskIds;
}
