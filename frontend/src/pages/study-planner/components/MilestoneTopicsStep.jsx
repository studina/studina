import dinaEmptyTopics from "../../../assets/dina/dina-empty-topics.png";

const LEVEL_LABELS = {
  weak: "Necesita refuerzo",
  progressing: "En progreso",
  strong: "Dominado",
};

export default function MilestoneTopicsStep({
  milestone,
  topics,
  error,
  loadingTopics,
  topicsLoadError,
  onMilestoneChange,
  onToggleTopic,
}) {
  const selectedCount =
    topics.filter(
      (topic) => topic.selected
    ).length;

  return (
    <div className="milestone-step">
      <section className="wizard-form-section">
        <div className="wizard-section-heading">
          <h3>Hito académico</h3>

          <p>
            Definí qué examen, entrega o trabajo
            querés planificar.
          </p>
        </div>

        <div className="milestone-fields">
          <label>
            Tipo de hito

            <select
              value={milestone.type}
              onChange={(event) =>
                onMilestoneChange(
                  "type",
                  event.target.value
                )
              }
            >
              <option value="">
                Seleccioná una opción
              </option>

              <option value="EXAM">
                Examen
              </option>

              <option value="MIDTERM">
                Parcial
              </option>

              <option value="FINAL_EXAM">
                Final
              </option>

              <option value="ASSIGNMENT">
                Trabajo práctico
              </option>

              <option value="DELIVERY">
                Entrega
              </option>

              <option value="OTHER">
                Otro
              </option>
            </select>
          </label>

          <label className="milestone-title-field">
            Nombre del hito

            <input
              type="text"
              placeholder="Ej. Primer parcial"
              value={milestone.title}
              onChange={(event) =>
                onMilestoneChange(
                  "title",
                  event.target.value
                )
              }
            />
          </label>

          <label className="milestone-description-field">
            Descripción

            <textarea
              rows="3"
              placeholder="Ej. Evaluación de las unidades 1, 2 y 3"
              value={milestone.description}
              onChange={(event) =>
                onMilestoneChange(
                  "description",
                  event.target.value
                )
              }
            />
          </label>
        </div>
      </section>

      <section className="wizard-form-section">
        <div className="wizard-section-heading">
          <h3>Temas de la materia</h3>

          <p>
            Seleccioná los temas que forman parte
            de este hito.
          </p>
        </div>

        {loadingTopics && (
          <div className="wizard-empty-topics">
            <div className="wizard-empty-topics-content">
              <p>
                Cargando temas...
              </p>

              <span>
                Studina está recuperando los temas
                detectados en tu materia.
              </span>
            </div>
          </div>
        )}

        {topicsLoadError && (
          <div
            className="wizard-validation-error"
            role="alert"
          >
            {topicsLoadError}
          </div>
        )}

        {!loadingTopics &&
          !topicsLoadError &&
          topics.length === 0 && (
            <div className="wizard-empty-topics">
              <img
                src={dinaEmptyTopics}
                alt=""
                aria-hidden="true"
              />

              <div className="wizard-empty-topics-content">
                <p>
                  No hay temas detectados
                </p>

                <span>
                  Subí material a la materia para
                  que Studina pueda detectar sus temas.
                </span>
              </div>
            </div>
          )}

        {topics.length > 0 && (
          <>
            <p className="milestone-topics-count">
              <strong>
                {selectedCount} de {topics.length}
              </strong>{" "}
              temas incluidos
            </p>

            <div className="milestone-topics-list">
              {topics.map((topic) => {
                const knownLevel =
                  Boolean(LEVEL_LABELS[topic.level]);
                const levelClass = knownLevel
                  ? `level-${topic.level}`
                  : "level-none";
                const levelLabel = topic.level
                  ? LEVEL_LABELS[topic.level] ?? topic.level
                  : "Sin empezar";
                const resourceCount =
                  Number(topic.totalResources) || 0;
                const resourceLabel =
                  resourceCount === 1
                    ? "1 recurso"
                    : `${resourceCount} recursos`;

                return (
                  <article
                    key={topic.id}
                    className={`milestone-topic-card${
                      topic.selected
                        ? " is-selected"
                        : ""
                    }`}
                  >
                    <label className="milestone-topic-select">
                      <input
                        type="checkbox"
                        className="milestone-topic-checkbox"
                        checked={topic.selected}
                        onChange={() =>
                          onToggleTopic(
                            topic.id
                          )
                        }
                      />

                      <span className="milestone-topic-content">
                        <strong className="milestone-topic-name">
                          {topic.title}
                        </strong>

                        <span className="milestone-topic-meta">
                          <span
                            className={`milestone-topic-status ${levelClass}`}
                          >
                            {levelLabel}
                          </span>

                          <span className="milestone-topic-resources">
                            {resourceLabel}
                          </span>
                        </span>
                      </span>
                    </label>
                  </article>
                );
              })}
            </div>
          </>
        )}
      </section>

      {error && (
        <div
          className="wizard-validation-error"
          role="alert"
        >
          {error}
        </div>
      )}
    </div>
  );
}
