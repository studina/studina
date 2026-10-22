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
import "./TrueFalsePage.css";
import dinaThinking from "../assets/dina/dina-thinking.png";
import dinaWritingForm from "../assets/dina/dina-writing-form.png";
import dinaHappyWriting from "../assets/dina/dina-happy-writing.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

const answerOptions = [
  { value: true, label: "Verdadero" },
  { value: false, label: "Falso" },
];

/**
 * Misma biblioteca, generación en segundo plano y registro de intentos que QuizPage; sólo
 * cambia el ejercicio: un enunciado y dos respuestas posibles.
 *
 * `initialGeneration` es un pedido acotado a un tema, que llega desde la pantalla de progreso:
 * {fileIds, topicName}. Se consume como estado inicial —App remonta la página con una key
 * nueva por pedido—, así que después se edita como cualquier formulario.
 */
export default function TrueFalsePage({ subject, user, onBack, initialGeneration }) {
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
  const [statementCount, setStatementCount] = useState(10);
  const [difficulty, setDifficulty] = useState("basic");
  const [loading, setLoading] = useState(Boolean(authorizationHeader));
  const [showGenerator, setShowGenerator] = useState(Boolean(initialGeneration));
  // Arranca con lo que la cola ya tenía: si el usuario se fue y volvió, las filas en curso
  // están desde el primer render.
  const [generation, setGeneration] = useState(() =>
    getGenerationQueue("trueFalse", subject.id),
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
  const [statementIndex, setStatementIndex] = useState(0);
  // null mientras no respondió; true o false según lo que eligió.
  const [selectedAnswer, setSelectedAnswer] = useState(null);
  const [score, setScore] = useState(0);
  const pendingAnswerRef = useRef(null);
  const pendingWritesRef = useRef(Promise.resolve());

  useEffect(() => {
    if (!authorizationHeader) return undefined;
    let active = true;
    Promise.all([
      apiFetch(
        `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`,
      ),
      apiFetch(
        `/api/true-false?subjectId=${encodeURIComponent(String(subject.id))}`,
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
            setsBody.error || "No se pudieron cargar los sets de verdadero o falso",
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
      clearGenerationError("trueFalse", subject.id);
    }, 3000);
    return () => clearTimeout(timer);
  }, [notice, subject.id]);

  // La generación vive fuera de React, así que la página sólo escucha: ve lo que quedó en
  // curso al volver y recibe los sets que van terminando.
  useEffect(() => subscribeToGenerations("trueFalse", subject.id, {
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
      kind: "trueFalse",
      subjectId: subject.id,
      title,
      payload: {
        subjectId: String(subject.id),
        title: title.trim(),
        fileIds: selectedFileIds,
        instructions: instructions.trim(),
        statementCount,
        difficulty,
      },
    });

    setShowGenerator(false);
    setError("");
  };

  const resetSitting = () => {
    setStatementIndex(0);
    setSelectedAnswer(null);
    setScore(0);
    pendingAnswerRef.current = null;
  };

  const openSet = (set) => {
    if (!Array.isArray(set.statements) || set.statements.length === 0) return;
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
        `/api/true-false/${encodeURIComponent(editingSetId)}`,
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
        `/api/true-false/${encodeURIComponent(setId)}`,
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

  const currentStatement = activeSet?.statements?.[statementIndex];
  const answered = selectedAnswer !== null;

  const enqueueBackgroundWrite = (label, operation) => {
    pendingWritesRef.current = pendingWritesRef.current
      .then(operation)
      .catch((writeError) => {
        console.error(`${label}:`, writeError);
      });
  };

  const answer = (value) => {
    if (answered || !currentStatement?.id || !activeSet?.id
      || pendingAnswerRef.current?.statementId === currentStatement.id) return;
    const attempt = {
      statementId: currentStatement.id,
      answer: value,
      attemptId: randomUuid(),
    };
    pendingAnswerRef.current = attempt;
    setSelectedAnswer(value);
    if (value === currentStatement.isTrue) {
      setScore((previousScore) => previousScore + 1);
    }

    // Fire-and-forget, como en los cuestionarios: el usuario no espera para seguir.
    enqueueBackgroundWrite("No se pudo guardar la respuesta de verdadero o falso", async () => {
      const response = await apiFetch(`/api/true-false/${encodeURIComponent(activeSet.id)}/attempts`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(attempt),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
    });
  };

  const nextStatement = () => {
    if (!answered) return;
    if (statementIndex + 1 >= activeSet.statements.length) {
      setActiveSet({ ...activeSet, completed: true });
      return;
    }
    setStatementIndex((previousIndex) => previousIndex + 1);
    setSelectedAnswer(null);
    pendingAnswerRef.current = null;
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
          <p>Cargando tus sets de verdadero o falso...</p>
        </div>
      </div>
    );
  }

  if (activeSet) {
    if (activeSet.completed) {
      return (
        <div className="quiz-page">
          <button className="btn-back" onClick={onBack}>
            ← Volver
          </button>
          <div className="quiz-result">
            <img
              src={dinaHappyWriting}
              alt="Dina celebrando el resultado del verdadero o falso"
            />
            <h1>{activeSet.title}</h1>
            <p className="quiz-result-label">Resultado final</p>
            <strong>
              {score} / {activeSet.statements.length}
            </strong>
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
            {subject.name} · {statementIndex + 1} / {activeSet.statements.length}
          </p>
        </header>
        <div className="quiz-progress">
          <span
            style={{
              width: `${((statementIndex + 1) / activeSet.statements.length) * 100}%`,
            }}
          />
        </div>
        <section className="quiz-question">
          <p className="true-false-prompt">¿Verdadero o falso?</p>
          <h2>{currentStatement.statement}</h2>
          <div className="quiz-options true-false-options">
            {answerOptions.map((option) => (
              <button
                type="button"
                key={option.label}
                className={`quiz-option true-false-option ${answered ? (option.value === currentStatement.isTrue ? "correct" : option.value === selectedAnswer ? "incorrect" : "") : ""}`}
                onClick={() => answer(option.value)}
                disabled={answered}
              >
                {option.label}
              </button>
            ))}
          </div>
          {answered && (
            <div className="quiz-explanation">
              <strong>
                {selectedAnswer === currentStatement.isTrue
                  ? "¡Correcto!"
                  : `Incorrecto: el enunciado es ${currentStatement.isTrue ? "verdadero" : "falso"}`}
              </strong>
              <p>{currentStatement.explanation}</p>
            </div>
          )}
          <button className="quiz-primary" disabled={!answered} onClick={nextStatement}>
            {statementIndex + 1 === activeSet.statements.length
              ? "Finalizar"
              : "Siguiente"}
          </button>
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
              Nuevo verdadero o falso
            </button>
          </div>
          {hasNoSets && (
            <div className="quiz-empty">
              <img src={dinaThinking} alt="Dina pensando enunciados" />
              <h2>Todavía no hay sets de verdadero o falso</h2>
              <p>
                {hasNoDocuments
                  ? "Para crear sets de verdadero o falso, primero tenés que cargar documentos en esta materia."
                  : "Creá uno a partir de los documentos de esta materia."}
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
                    Dina está preparando tu verdadero o falso...
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
                        {Array.isArray(set.statements) ? set.statements.length : 0}{" "}
                        enunciados - {difficultyLabels[set.difficulty] || "Básico"}
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
          <img src={dinaWritingForm} alt="Dina preparando enunciados" />
          <h1>Generá tu verdadero o falso</h1>
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
          <label htmlFor="true-false-title">Nombre del set</label>
          <input
            id="true-false-title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Verdadero o falso #1"
            maxLength={160}
          />
          <label htmlFor="true-false-instructions">Indicaciones adicionales</label>
          <textarea
            id="true-false-instructions"
            value={instructions}
            onChange={(event) => setInstructions(event.target.value)}
            placeholder="Por ejemplo: focalizá en las diferencias entre conceptos."
            rows={3}
          />
          <label htmlFor="statement-count">
            Cantidad de enunciados: {statementCount}
          </label>
          <input
            id="statement-count"
            type="range"
            min="5"
            max="20"
            value={statementCount}
            onChange={(event) => setStatementCount(Number(event.target.value))}
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
            Generar verdadero o falso
          </button>
        </div>
      </div>
    </div>
  );
}
