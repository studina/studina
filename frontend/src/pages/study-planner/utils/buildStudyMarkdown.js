const getMilestoneTypeLabel = (type) => {
  const labels = {
    EXAM: "Examen",
    MIDTERM: "Parcial",
    FINAL_EXAM: "Final",
    ASSIGNMENT: "Trabajo práctico",
    DELIVERY: "Entrega",
    OTHER: "Otro",
  };

  return labels[type] || "No especificado";
};

export const buildStudyMarkdown = ({
  milestone,
  topics,
}) => {
  const selectedTopics = topics.filter(
    (topic) => topic.title.trim()
  );

  const topicsMarkdown = selectedTopics
    .map((topic, index) => {
      const scope =
        topic.scope.trim() ||
        "No especificado";

      const estimatedMinutes =
        Number(topic.estimatedMinutes) || 0;

      return `### ${index + 1}. ${topic.title.trim()}

- Alcance: ${scope}
- Duración estimada: ${estimatedMinutes} minutos`;
    })
    .join("\n\n");

  return `# Planificación académica

## Hito

- Tipo: ${getMilestoneTypeLabel(
    milestone.type
  )}
- Nombre: ${
    milestone.title.trim() ||
    "No especificado"
  }
- Descripción: ${
    milestone.description.trim() ||
    "No especificada"
  }

## Temas informados por el usuario

${
  topicsMarkdown ||
  "Todavía no se informaron temas."
}`;
};