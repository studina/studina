import { apiFetch } from "../lib/session";

export async function getStudyPlanTopics(subjectId) {
  const response = await apiFetch(
    `/api/progress?subjectId=${encodeURIComponent(
      subjectId
    )}`
  );

  const body = await response
    .json()
    .catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      body.message ||
        body.error ||
        "No se pudieron cargar los temas de la materia."
    );
  }

  return body;
}

export async function generateStudyPlan(planData) {
  const response = await apiFetch(
    "/api/study-plans/generate",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(planData),
    }
  );

  const body = await response
    .json()
    .catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      body.message ||
        body.error ||
        "No se pudo generar el plan."
    );
  }

  return body;
}