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

export async function generateStudyPlan(subjectId, planData) {
  const response = await apiFetch(
    `/api/study-plans/${encodeURIComponent(subjectId)}/generate`,
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

export async function getLatestStudyPlan(subjectId) {
  const response = await apiFetch(
    `/api/study-plans/${encodeURIComponent(subjectId)}`
  );

  if (response.status === 204) {
    return null;
  }

  const body = await response
    .json()
    .catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      body.message ||
        body.error ||
        "No se pudo cargar el plan de estudio."
    );
  }

  return body;
}

export async function deleteStudyPlan(subjectId) {
  const response = await apiFetch(
    `/api/study-plans/${encodeURIComponent(subjectId)}`,
    {
      method: "DELETE",
    }
  );

  if (!response.ok) {
    const body = await response
      .json()
      .catch(() => ({}));

    throw new Error(
      body.message ||
        body.error ||
        "No se pudo borrar el plan de estudio."
    );
  }
}