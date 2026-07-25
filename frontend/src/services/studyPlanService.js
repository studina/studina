export async function generateStudyPlan(planData) {
  const response = await fetch(
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