import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { randomUuid } from "../lib/uuid";
import { apiFetch } from "../lib/session";
import SelectAllFiles from "../components/SelectAllFiles";
import {
  clearGenerationError,
  enqueueGeneration,
  getGenerationQueue,
  subscribeToGenerations,
} from "../lib/generationQueue";
import "../styles/library.css";
import "./QuizPage.css";
import "./OpenQuestionsPage.css";
import dinaThinking from "../assets/dina/dina-thinking.png";
import dinaWritingForm from "../assets/dina/dina-writing-form.png";
import dinaHappyWriting from "../assets/dina/dina-happy-writing.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

/** Los niveles de la corrección, de mejor a peor. El score lo decide el backend. */
const verdicts = {
  perfect: { label: "Perfecta", tone: "good" },
  correct: { label: "Correcta", tone: "good" },
  partially_correct: { label: "Parcialmente correcta", tone: "mid" },
  partially_incorrect: { label: "Parcialmente incorrecta", tone: "bad" },
  incorrect: { label: "Incorrecta", tone: "bad" },
};

const MAX_ANSWER_LENGTH = 4000;

/**
 * Misma biblioteca y generación en segundo plano que TrueFalsePage; cambia el ejercicio: el
 * estudiante escribe la respuesta y Dina la corrige.
 *
 * A diferencia de los otros recursos, la respuesta no se guarda en segundo plano: la
 * corrección viene en la respuesta del POST, así que el estudiante la espera. Si el envío
 * falla, reenviar el mismo texto reusa el mismo `attemptId` y el backend devuelve la
 * corrección ya hecha en lugar de corregir dos veces.
 *
 * `initialGeneration` es un pedido acotado a un tema, que llega desde la pantalla de progreso:
 * {fileIds, topicName}. Se consume como estado inicial —App remonta la página con una key
 * nueva por pedido—, así que después se edita como cualquier formulario.
 */
export default function OpenQuestionsPage({ subject, user, onBack, initialGeneration }) {
  const authorizationHeader = user?.token ? `Bearer ${user.token}` : null;
  const [files, setFiles] = useState([]);
  const [sets, setSets] = useState([]);
  const [selectedFileIds, setSelectedFileIds] = useState(initialGeneration?.fileIds ?? []);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState(
    initialGeneration?.topicName
      ? `Enfocate únicamente en el tema: ${initialGeneration.topicName}.`
      : "",
  );
  const [questionCount, setQuestionCount] = useState(5);
  const [difficulty, setDifficulty] = useState("basic");
  const [loading, setLoading] = useState(Boolean(authorizationHeader));
  const [showGenerator, setShowGenerator] = useState(Boolean(initialGeneration));
  // Arranca con lo que la cola ya tenía: si el usuario se fue y volvió, las filas en curso
  // están desde el primer render.
  const [generation, setGeneration] = useState(() =>
    getGenerationQueue("openQuestions", subject.id),
  );
  const pendingSets = generation.pending;
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [editingSetId, setEditingSetId] = useState(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState(null);
  const [deletingSetId, setDeletingSetId] = useState(null);
  const [activeSet, setActiveSet] = useState(null);
  const [questionIndex, setQuestionIndex] = useState(0);
  const [draft, setDraft] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [evaluation, setEvaluation] = useState(null);
  const [submitError, setSubmitError] = useState("");
  // La última corrección de cada pregunta: es la que cuenta para el resultado final.
  const [latestScores, setLatestScores] = useState({});
  // El intento que falló al enviarse, para reenviarlo con el mismo attemptId.
  const failedAttemptRef = useRef(null);
  // Cambia cada vez que se cambia de pregunta: una corrección que llega tarde se descarta.
  const sittingRef = useRef(0);

  useEffect(() => {
    if (!authorizationHeader) return undefined;
    let active = true;
    Promise.all([
      apiFetch(
        `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`,
      ),
      apiFetch(
        `/api/open-questions?subjectId=${encodeURIComponent(String(subject.id))}`,
      ),
    ])
      .then(async ([filesResponse, setsResponse]) => {
        const filesBody = await filesResponse.json().catch(() => ({}));
        const setsBody = await setsResponse.json().catch(() => ({}));
        if (!filesResponse.ok)
          throw new Error(
            filesBody.error || "No se pudieron cargar los documentos",
          );
        if (!setsResponse.ok)
          throw new Error(
            setsBody.error || "No se pudieron cargar los sets de preguntas abiertas",
          );
        return {
          files: Array.isArray(filesBody.data) ? filesBody.data : [],
          sets: Array.isArray(setsBody) ? setsBody : [],
        };
      })
      .then(({ files: loadedFiles, sets: loadedSets }) => {
        if (!active) return;
        setFiles(loadedFiles);
        setSets(loadedSets);
      })
      .catch((requestError) => {
        if (active) setError(requestError.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authorizationHeader, subject.id]);

  // Una generación pudo fallar mientras el usuario estaba en otra pantalla, así que el
  // aviso sale del toast local o del error que quedó guardado en la cola.
  const notice = toast || generation.error;

  // El aviso de error es pasajero: se va solo, sin que haya que cerrarlo.
  useEffect(() => {
    if (!notice) return undefined;
    const timer = setTimeout(() => {
      setToast("");
      clearGenerationError("openQuestions", subject.id);
    }, 3000);
    return () => clearTimeout(timer);
  }, [notice, subject.id]);

  // La generación vive fuera de React, así que la página sólo escucha: ve lo que quedó en
  // curso al volver y recibe los sets que van terminando.
  useEffect(() => subscribeToGenerations("openQuestions", subject.id, {
    onChange: setGeneration,
    onGenerated: (generatedSet) => {
      // La lista puede haber llegado ya con el set si terminó entre el pedido y su
      // respuesta, así que se deduplica por id.
      setSets((previousSets) =>
        previousSets.some((item) => item.id === generatedSet.id)
          ? previousSets
          : [generatedSet, ...previousSets],
      );
    },
  }), [subject.id]);

  const toggleFile = (fileId) => {
    setSelectedFileIds((previousIds) =>
      previousIds.includes(fileId)
        ? previousIds.filter((id) => id !== fileId)
        : [...previousIds, fileId],
    );
  };

  // Generar no deja al usuario esperando en el formulario: cierra la vista, agrega el set a
  // la lista como pendiente y resuelve en segundo plano.
  const generate = () => {
    if (!authorizationHeader || selectedFileIds.length === 0) return;

    enqueueGeneration({
      kind: "openQuestions",
      subjectId: subject.id,
      title,
      payload: {
        subjectId: String(subject.id),
        title: title.trim(),
        fileIds: selectedFileIds,
        instructions: instructions.trim(),
        questionCount,
        difficulty,
      },
    });

    setShowGenerator(false);
    setError("");
  };

  /** Deja la pregunta lista para una respuesta nueva. */
  const resetQuestion = () => {
    sittingRef.current += 1;
    setDraft("");
    setSubmitting(false);
    setEvaluation(null);
    setSubmitError("");
    failedAttemptRef.current = null;
  };

  const resetSitting = () => {
    setQuestionIndex(0);
    setLatestScores({});
    resetQuestion();
  };

  const openSet = (set) => {
    if (!Array.isArray(set.questions) || set.questions.length === 0) return;
    const setToOpen = { ...set };
    delete setToOpen.completed;
    setActiveSet(setToOpen);
    resetSitting();
  };

  const startEditingTitle = (set) => {
    setEditingSetId(set.id);
    setEditingTitle(set.title || "");
    setError("");
  };

  const saveTitle = async () => {
    const trimmedTitle = editingTitle.trim();
    if (!authorizationHeader || !editingSetId || !trimmedTitle || savingTitle)
      return;

    setSavingTitle(true);
    setError("");
    try {
      const response = await apiFetch(
        `/api/open-questions/${encodeURIComponent(editingSetId)}`,
        {
          method: "PATCH",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ title: trimmedTitle }),
        },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(body.error || "No se pudo actualizar el nombre del set");
      setSets((previousSets) =>
        previousSets.map((set) =>
          set.id === editingSetId ? { ...set, ...body } : set,
        ),
      );
      setEditingSetId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSavingTitle(false);
    }
  };

  const deleteSet = async (setId) => {
    if (!authorizationHeader || deletingSetId) return;

    setDeletingSetId(setId);
    setError("");
    try {
      const response = await apiFetch(
        `/api/open-questions/${encodeURIComponent(setId)}`,
        { method: "DELETE" },
      );
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || "No se pudo eliminar el set");
      }
      setSets((previousSets) => previousSets.filter((set) => set.id !== setId));
      setConfirmingDeleteId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setDeletingSetId(null);
    }
  };

  const currentQuestion = activeSet?.questions?.[questionIndex];
  const evaluated = evaluation !== null;
  const answerText = draft.trim();

  const submitAnswer = async () => {
    if (!answerText || submitting || evaluated || !currentQuestion?.id || !activeSet?.id) return;
    // Reenviar lo mismo que falló es el mismo intento; si el texto cambió, es uno nuevo.
    const failed = failedAttemptRef.current;
    const attempt = failed?.questionId === currentQuestion.id && failed.answer === answerText
      ? failed
      : { questionId: currentQuestion.id, answer: answerText, attemptId: randomUuid() };
    const sitting = sittingRef.current;

    setSubmitting(true);
    setSubmitError("");
    try {
      const response = await apiFetch(
        `/api/open-questions/${encodeURIComponent(activeSet.id)}/attempts`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify(attempt),
        },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || "No se pudo corregir la respuesta");
      if (sitting !== sittingRef.current) return;
      failedAttemptRef.current = null;
      setEvaluation(body);
      setLatestScores((previousScores) => ({
        ...previousScores,
        [currentQuestion.id]: { verdict: body.verdict, score: Number(body.score) || 0 },
      }));
    } catch (requestError) {
      if (sitting !== sittingRef.current) return;
      failedAttemptRef.current = attempt;
      setSubmitError(requestError.message);
    } finally {
      if (sitting === sittingRef.current) setSubmitting(false);
    }
  };

  const nextQuestion = () => {
    if (!evaluated) return;
    if (questionIndex + 1 >= activeSet.questions.length) {
      setActiveSet({ ...activeSet, completed: true });
      return;
    }
    setQuestionIndex((previousIndex) => previousIndex + 1);
    resetQuestion();
  };

  // Salir del set sin salir de la sección: "Volver" lleva al menú de la materia, así que
  // hace falta algo que devuelva al listado.
  const returnToSetList = () => {
    setActiveSet(null);
    resetSitting();
  };

  if (loading) {
    return (
      <div className="quiz-page quiz-page--loading">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="page-loading" role="status" aria-live="polite">
          <span className="loader loader--lg" aria-hidden="true" />
          <p>Cargando tus preguntas abiertas...</p>
        </div>
      </div>
    );
  }

  if (activeSet) {
    if (activeSet.completed) {
      const results = Object.values(latestScores);
      const average = results.length === 0
        ? 0
        : results.reduce((total, result) => total + result.score, 0) / results.length;
      return (
        <div className="quiz-page">
          <button className="btn-back" onClick={onBack}>
            ← Volver
          </button>
          <div className="quiz-result">
            <img
              src={dinaHappyWriting}
              alt="Dina celebrando el resultado de las preguntas abiertas"
            />
            <h1>{activeSet.title}</h1>
            <p className="quiz-result-label">Resultado final</p>
            <strong>{Math.round(average * 100)}%</strong>
            <ul className="open-result-breakdown">
              {Object.entries(verdicts).map(([verdict, { label, tone }]) => {
                const count = results.filter((result) => result.verdict === verdict).length;
                return count === 0 ? null : (
                  <li key={verdict} className={`open-verdict open-verdict--${tone}`}>
                    {label}: {count}
                  </li>
                );
              })}
            </ul>
            <div className="quiz-result-actions">
              <button className="quiz-primary" onClick={() => openSet(activeSet)}>
                Reintentar
              </button>
              <button className="quiz-secondary" onClick={returnToSetList}>
                Ver mis sets
              </button>
            </div>
          </div>
        </div>
      );
    }
    const verdict = evaluated ? verdicts[evaluation.verdict] : null;
    const corrections = Array.isArray(evaluation?.corrections) ? evaluation.corrections : [];
    const missingPoints = Array.isArray(evaluation?.missing_points) ? evaluation.missing_points : [];
    return (
      <div className="quiz-page quiz-page--take">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <button type="button" className="quiz-secondary" onClick={returnToSetList}>
          ← Ver mis sets
        </button>
        <header className="quiz-take-header">
          <h1>{activeSet.title}</h1>
          <p>
            {subject.name} · {questionIndex + 1} / {activeSet.questions.length}
          </p>
        </header>
        <div className="quiz-progress">
          <span
            style={{
              width: `${((questionIndex + 1) / activeSet.questions.length) * 100}%`,
            }}
          />
        </div>
        <section className="quiz-question">
          <h2>{currentQuestion.question}</h2>
          <label className="open-answer-label" htmlFor="open-answer">
            Tu respuesta
          </label>
          <textarea
            id="open-answer"
            className="open-answer"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="Escribí tu respuesta con tus palabras."
            rows={6}
            maxLength={MAX_ANSWER_LENGTH}
            disabled={submitting || evaluated}
          />
          {submitting && (
            <p className="open-evaluating" role="status" aria-live="polite">
              <span className="loader" aria-hidden="true" />
              Dina está corrigiendo tu respuesta...
            </p>
          )}
          {submitError && <p className="quiz-error">{submitError}</p>}
          {!evaluated && (
            <button
              className="quiz-primary"
              disabled={!answerText || submitting}
              onClick={submitAnswer}
            >
              {submitError ? "Reintentar envío" : "Enviar respuesta"}
            </button>
          )}
          {evaluated && (
            <div className="quiz-explanation open-evaluation">
              <span className={`open-verdict open-verdict--${verdict?.tone ?? "mid"}`}>
                {verdict?.label ?? evaluation.verdict}
              </span>
              <p>{evaluation.feedback}</p>
              {corrections.length > 0 && (
                <>
                  <strong>Correcciones</strong>
                  <ul>
                    {corrections.map((correction, index) => (
                      <li key={index}>
                        <span className="open-issue">{correction.issue}</span>
                        {" → "}
                        {correction.correction}
                      </li>
                    ))}
                  </ul>
                </>
              )}
              {missingPoints.length > 0 && (
                <>
                  <strong>Te faltó mencionar</strong>
                  <ul>
                    {missingPoints.map((point, index) => (
                      <li key={index}>{point}</li>
                    ))}
                  </ul>
                </>
              )}
              <details>
                <summary>Ver respuesta modelo</summary>
                <p>{currentQuestion.reference_answer}</p>
              </details>
            </div>
          )}
          {evaluated && (
            <div className="open-actions">
              <button className="quiz-secondary" onClick={resetQuestion}>
                Intentar de nuevo
              </button>
              <button className="quiz-primary" onClick={nextQuestion}>
                {questionIndex + 1 === activeSet.questions.length
                  ? "Finalizar"
                  : "Siguiente"}
              </button>
            </div>
          )}
        </section>
      </div>
    );
  }

  if (!showGenerator) {
    const hasNoDocuments = files.length === 0;
    // Un set en curso ya ocupa la lista: mostrar "todavía no hay sets" al lado de una fila
    // generándose sería contradictorio.
    const hasNoSets = sets.length === 0 && pendingSets.length === 0;
    return (
      <div className="quiz-page">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="library">
          <div className="library-header">
            <button
              className="quiz-primary"
              disabled={hasNoDocuments}
              onClick={() => setShowGenerator(true)}
            >
              Nuevas preguntas abiertas
            </button>
          </div>
          {hasNoSets && (
            <div className="quiz-empty">
              <img src={dinaThinking} alt="Dina pensando preguntas" />
              <h2>Todavía no hay preguntas abiertas</h2>
              <p>
                {hasNoDocuments
                  ? "Para crear preguntas abiertas, primero tenés que cargar documentos en esta materia."
                  : "Creá un set a partir de los documentos de esta materia."}
              </p>
            </div>
          )}
          <div className="library-list">
            {pendingSets.map((pending) => (
              <div
                className="library-item library-item--pending"
                key={pending.id}
                aria-busy="true"
              >
                <div className="library-item-pending">
                  <span className="library-item-pending-title">{pending.title}</span>
                  <span className="library-item-pending-status">
                    <span className="loader" aria-hidden="true" />
                    Dina está preparando tus preguntas...
                  </span>
                </div>
              </div>
            ))}
            {sets.map((set) => (
              <div className="library-item" key={set.id}>
                {editingSetId === set.id ? (
                  <div className="quiz-set-edit">
                    <input
                      aria-label={`Nombre de ${set.title}`}
                      value={editingTitle}
                      onChange={(event) => setEditingTitle(event.target.value)}
                      maxLength={160}
                    />
                    <div>
                      <button
                        type="button"
                        onClick={saveTitle}
                        disabled={!editingTitle.trim() || savingTitle}
                      >
                        {savingTitle ? "Guardando..." : "Guardar"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingSetId(null)}
                        disabled={savingTitle}
                      >
                        Cancelar
                      </button>
                    </div>
                  </div>
                ) : confirmingDeleteId === set.id ? (
                  <div className="quiz-set-edit">
                    <span>
                      ¿Eliminar “{set.title}”? Esta acción no se puede deshacer.
                    </span>
                    <div>
                      <button
                        type="button"
                        onClick={() => deleteSet(set.id)}
                        disabled={deletingSetId === set.id}
                      >
                        {deletingSetId === set.id ? "Eliminando..." : "Confirmar"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setConfirmingDeleteId(null)}
                        disabled={deletingSetId === set.id}
                      >
                        Cancelar
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <button
                      type="button"
                      className="quiz-set-open"
                      onClick={() => openSet(set)}
                    >
                      <strong>{set.title}</strong>
                      <span>
                        {Array.isArray(set.questions) ? set.questions.length : 0}{" "}
                        preguntas - {difficultyLabels[set.difficulty] || "Básico"}
                      </span>
                    </button>
                    <button
                      type="button"
                      className="quiz-set-rename"
                      onClick={() => startEditingTitle(set)}
                    >
                      Editar nombre
                    </button>
                    <button
                      type="button"
                      className="quiz-set-delete"
                      onClick={() => setConfirmingDeleteId(set.id)}
                    >
                      Eliminar
                    </button>
                  </>
                )}
              </div>
            ))}
          </div>
        </div>
        {error && <p className="quiz-error">{error}</p>}

        {/* Por portal: un ancestro con transform o filter recorta el
            position: fixed y el aviso quedaría dentro de la página. */}
        {notice &&
          createPortal(
            <div className="library-toast" role="alert">
              {notice}
            </div>,
            document.body,
          )}
      </div>
    );
  }

  return (
    <div className="quiz-page">
      <button className="btn-back" onClick={onBack}>
        ← Volver
      </button>
      <button className="quiz-secondary" onClick={() => setShowGenerator(false)}>
        ← Ver mis sets
      </button>
      <div className="quiz-generator">
        <div className="quiz-generator-intro">
          <img src={dinaWritingForm} alt="Dina preparando preguntas" />
          <h1>Generá tus preguntas abiertas</h1>
          <p>Elegí los documentos de {subject.name} que querés usar.</p>
        </div>
        <div className="quiz-form">
          <label>Documentos</label>
          <div className="quiz-files">
            <SelectAllFiles
              className="quiz-files-all"
              files={files}
              selectedFileIds={selectedFileIds}
              onChange={setSelectedFileIds}
            />
            {files.map((file) => (
              <label key={file.id}>
                <input
                  type="checkbox"
                  checked={selectedFileIds.includes(file.id)}
                  onChange={() => toggleFile(file.id)}
                />
                {file.filename || file.name}
              </label>
            ))}
          </div>
          <label htmlFor="open-questions-title">Nombre del set</label>
          <input
            id="open-questions-title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Preguntas abiertas #1"
            maxLength={160}
          />
          <label htmlFor="open-questions-instructions">Indicaciones adicionales</label>
          <textarea
            id="open-questions-instructions"
            value={instructions}
            onChange={(event) => setInstructions(event.target.value)}
            placeholder="Por ejemplo: preguntá por las causas y consecuencias de cada proceso."
            rows={3}
          />
          <label htmlFor="question-count">
            Cantidad de preguntas: {questionCount}
          </label>
          <input
            id="question-count"
            type="range"
            min="3"
            max="10"
            value={questionCount}
            onChange={(event) => setQuestionCount(Number(event.target.value))}
          />
          <label>Dificultad: {difficultyLabels[difficulty]}</label>
          <div
            className="answer-count-options"
            role="radiogroup"
            aria-label="Dificultad"
          >
            {Object.entries(difficultyLabels).map(([value, label]) => (
              <button
                type="button"
                key={value}
                className={`answer-count-option ${difficulty === value ? "selected" : ""}`}
                aria-pressed={difficulty === value}
                onClick={() => setDifficulty(value)}
              >
                {label}
              </button>
            ))}
          </div>
          {error && <p className="quiz-error">{error}</p>}
          <button
            className="quiz-primary"
            disabled={selectedFileIds.length === 0}
            onClick={generate}
          >
            Generar preguntas abiertas
          </button>
        </div>
      </div>
    </div>
  );
}
