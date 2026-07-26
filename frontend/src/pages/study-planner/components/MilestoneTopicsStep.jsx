import dinaEmptyTopics from "../../../assets/dina/dina-empty-topics.png";

export default function MilestoneTopicsStep({
  milestone,
  topics,
  error,
  onMilestoneChange,
  onTopicChange,
  onAddTopic,
  onRemoveTopic,
}) {
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
              placeholder="Ej. Primer parcial de Base de Datos"
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
          <h3>Temas</h3>

          <p>
            Cargá los contenidos que forman parte
            del hito.
          </p>
        </div>

        {topics.length === 0 && (
          <div className="wizard-empty-topics">
            <img
              src={dinaEmptyTopics}
              alt=""
              aria-hidden="true"
            />

            <div className="wizard-empty-topics-content">
              <p>Todavía no agregaste temas</p>
              <span>
                Sumá los contenidos que necesitás estudiar y
                asignales una duración estimada.
              </span>

              <button
                type="button"
                className="wizard-add-topic-button"
                onClick={onAddTopic}
              >
                ＋ Agregar primer tema
              </button>
            </div>
          </div>
        )}
        <div className="milestone-topics-list">
          {topics.map((topic, index) => (
            <article
              key={topic.id}
              className="milestone-topic-card"
            >
              <div className="milestone-topic-header">
                <strong>
                  Tema {index + 1}
                </strong>

                <button
                  type="button"
                  className="milestone-topic-delete"
                  onClick={() =>
                    onRemoveTopic(topic.id)
                  }
                  aria-label={`Eliminar tema ${index + 1
                    }`}
                  title="Eliminar tema"
                >
                  ✕
                </button>
              </div>

              <div className="milestone-topic-fields">
                <label>
                  Nombre

                  <input
                    type="text"
                    placeholder="Ej. Normalización"
                    value={topic.title}
                    onChange={(event) =>
                      onTopicChange(
                        topic.id,
                        "title",
                        event.target.value
                      )
                    }
                  />
                </label>

                <label>
                  Duración estimada

                  <div className="milestone-duration-field">
                    <input
                      type="number"
                      min="1"
                      step="15"
                      value={
                        topic.estimatedMinutes
                      }
                      onChange={(event) =>
                        onTopicChange(
                          topic.id,
                          "estimatedMinutes",
                          event.target.value
                        )
                      }
                    />

                    <span>min</span>
                  </div>
                </label>

                <label className="milestone-topic-scope">
                  Alcance

                  <textarea
                    rows="2"
                    placeholder="Ej. Dependencias funcionales, claves y tercera forma normal"
                    value={topic.scope}
                    onChange={(event) =>
                      onTopicChange(
                        topic.id,
                        "scope",
                        event.target.value
                      )
                    }
                  />
                </label>
              </div>
            </article>
          ))}
        </div>

        {topics.length > 0 && (
          <button
            type="button"
            className="wizard-add-topic-button"
            onClick={onAddTopic}
          >
            ＋ Agregar tema
          </button>
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
