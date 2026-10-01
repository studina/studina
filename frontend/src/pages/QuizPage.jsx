import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { randomUuid } from "../lib/uuid";
import "../styles/library.css";
import "./QuizPage.css";
import dinaWritingForm from "../assets/dina/dina-writing-form.png";
import dinaHappyWriting from "../assets/dina/dina-happy-writing.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

export default function QuizPage({ subject, user, onBack }) {
  const authorizationHeader = user?.token ? `Bearer ${user.token}` : null;
  const [files, setFiles] = useState([]);
  const [quizzes, setQuizzes] = useState([]);
  const [selectedFileIds, setSelectedFileIds] = useState([]);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [questionCount, setQuestionCount] = useState(10);
  const [answerCount, setAnswerCount] = useState(4);
  const [difficulty, setDifficulty] = useState("basic");
  const [loading, setLoading] = useState(Boolean(authorizationHeader));
  const [showGenerator, setShowGenerator] = useState(false);
  const [pendingQuizzes, setPendingQuizzes] = useState([]);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [editingQuizId, setEditingQuizId] = useState(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState(null);
  const [deletingQuizId, setDeletingQuizId] = useState(null);
  const [activeQuiz, setActiveQuiz] = useState(null);
  const [questionIndex, setQuestionIndex] = useState(0);
  const [selectedOption, setSelectedOption] = useState(null);
  const [score, setScore] = useState(0);
  const pendingAnswerRef = useRef(null);
  const pendingWritesRef = useRef(Promise.resolve());

  useEffect(() => {
    if (!authorizationHeader) return undefined;
    let active = true;
    Promise.all([
      fetch(
        `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`,
        {
          headers: { Authorization: authorizationHeader },
        },
      ),
      fetch(
        `/api/quizzes?subjectId=${encodeURIComponent(String(subject.id))}`,
        {
          headers: { Authorization: authorizationHeader },
        },
      ),
    ])
      .then(async ([filesResponse, quizzesResponse]) => {
        const filesBody = await filesResponse.json().catch(() => ({}));
        const quizzesBody = await quizzesResponse.json().catch(() => ({}));
        if (!filesResponse.ok)
          throw new Error(
            filesBody.error || "No se pudieron cargar los documentos",
          );
        if (!quizzesResponse.ok)
          throw new Error(
            quizzesBody.error || "No se pudieron cargar los cuestionarios",
          );
        return {
          files: Array.isArray(filesBody.data) ? filesBody.data : [],
          quizzes: Array.isArray(quizzesBody) ? quizzesBody : [],
        };
      })
      .then(({ files: loadedFiles, quizzes: loadedQuizzes }) => {
        if (!active) return;
        setFiles(loadedFiles);
        setQuizzes(loadedQuizzes);
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

  // El aviso de error es pasajero: se va solo, sin que haya que cerrarlo.
  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(""), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

  const toggleFile = (fileId) => {
    setSelectedFileIds((previousIds) =>
      previousIds.includes(fileId)
        ? previousIds.filter((id) => id !== fileId)
        : [...previousIds, fileId],
    );
  };

  // Generar no deja al usuario esperando en el formulario: cierra la vista, agrega
  // el cuestionario a la lista como pendiente y resuelve en segundo plano. Se puede
  // lanzar más de uno y cada uno se resuelve por su cuenta.
  const generate = async () => {
    if (!authorizationHeader || selectedFileIds.length === 0) return;

    const pending = {
      id: randomUuid(),
      title: title.trim() || "Cuestionario nuevo",
    };
    setPendingQuizzes((previous) => [pending, ...previous]);
    setShowGenerator(false);
    setError("");

    try {
      const response = await fetch("/api/ai/files/quizzes", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: authorizationHeader,
        },
        body: JSON.stringify({
          subjectId: String(subject.id),
          title: title.trim(),
          fileIds: selectedFileIds,
          instructions: instructions.trim(),
          questionCount,
          answerCount,
          difficulty,
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(body.error || "No se pudo generar el cuestionario");
      if (!Array.isArray(body.questions) || body.questions.length === 0)
        throw new Error("La IA no devolvió preguntas válidas");

      // Aparece en la lista listo para abrir; no se abre solo, porque el usuario
      // ya se fue del formulario y puede estar haciendo otra cosa.
      setQuizzes((previousQuizzes) => [body, ...previousQuizzes]);
    } catch (requestError) {
      console.error("Error generando el cuestionario:", requestError);
      setToast(requestError.message);
    } finally {
      setPendingQuizzes((previous) =>
        previous.filter((item) => item.id !== pending.id),
      );
    }
  };

  const openQuiz = (quiz) => {
    if (!Array.isArray(quiz.questions) || quiz.questions.length === 0) return;
    const quizToOpen = { ...quiz };
    delete quizToOpen.completed;
    setActiveQuiz(quizToOpen);
    setQuestionIndex(0);
    setSelectedOption(null);
    setScore(0);
    pendingAnswerRef.current = null;
  };

  const startEditingTitle = (quiz) => {
    setEditingQuizId(quiz.id);
    setEditingTitle(quiz.title || "");
    setError("");
  };

  const saveTitle = async () => {
    const trimmedTitle = editingTitle.trim();
    if (!authorizationHeader || !editingQuizId || !trimmedTitle || savingTitle)
      return;

    setSavingTitle(true);
    setError("");
    try {
      const response = await fetch(
        `/api/quizzes/${encodeURIComponent(editingQuizId)}`,
        {
          method: "PATCH",
          headers: {
            "Content-Type": "application/json",
            Authorization: authorizationHeader,
          },
          body: JSON.stringify({ title: trimmedTitle }),
        },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(
          body.error || "No se pudo actualizar el nombre del cuestionario",
        );
      setQuizzes((previousQuizzes) =>
        previousQuizzes.map((quiz) =>
          quiz.id === editingQuizId ? { ...quiz, ...body } : quiz,
        ),
      );
      setEditingQuizId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSavingTitle(false);
    }
  };

  const deleteQuiz = async (quizId) => {
    if (!authorizationHeader || deletingQuizId) return;

    setDeletingQuizId(quizId);
    setError("");
    try {
      const response = await fetch(
        `/api/quizzes/${encodeURIComponent(quizId)}`,
        { method: "DELETE", headers: { Authorization: authorizationHeader } },
      );
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || "No se pudo eliminar el cuestionario");
      }
      setQuizzes((previousQuizzes) =>
        previousQuizzes.filter((quiz) => quiz.id !== quizId),
      );
      setConfirmingDeleteId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setDeletingQuizId(null);
    }
  };

  const currentQuestion = activeQuiz?.questions?.[questionIndex];
  const enqueueBackgroundWrite = (label, operation) => {
    pendingWritesRef.current = pendingWritesRef.current
      .then(operation)
      .catch((writeError) => {
        console.error(`${label}:`, writeError);
      });
  };

  const answer = (optionId) => {
    if (selectedOption || !currentQuestion?.id || !activeQuiz?.id
      || pendingAnswerRef.current?.questionId === currentQuestion.id) return;
    const attempt = {
      questionId: currentQuestion.id,
      optionId,
      attemptId: randomUuid(),
    };
    pendingAnswerRef.current = attempt;
    setSelectedOption(optionId);
    if (optionId === currentQuestion.correctOptionId) {
      setScore((previousScore) => previousScore + 1);
    }

    enqueueBackgroundWrite("No se pudo guardar la respuesta del cuestionario", async () => {
      const response = await fetch(`/api/quizzes/${encodeURIComponent(activeQuiz.id)}/attempts`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: authorizationHeader,
        },
        body: JSON.stringify({
          questionId: attempt.questionId,
          selectedOptionId: attempt.optionId,
          attemptId: attempt.attemptId,
        }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
    });
  };
  const nextQuestion = () => {
    if (!selectedOption) return;
    if (questionIndex + 1 >= activeQuiz.questions.length) {
      setActiveQuiz({ ...activeQuiz, completed: true });
      return;
    }
    setQuestionIndex((previousIndex) => previousIndex + 1);
    setSelectedOption(null);
    pendingAnswerRef.current = null;
  };

  // Leaving is local state only: whether a sitting was finished or abandoned is derived from
  // the attempts that share its session id.
  const leaveActiveQuiz = () => {
    onBack();
  };

  // Salir del cuestionario sin salir de la sección: "Volver" lleva al menú de la
  // materia, así que hace falta algo que devuelva al listado.
  const returnToQuizList = () => {
    setActiveQuiz(null);
    setQuestionIndex(0);
    setSelectedOption(null);
    setScore(0);
    pendingAnswerRef.current = null;
  };

  if (loading) {
    return (
      <div className="quiz-page quiz-page--loading">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="page-loading" role="status" aria-live="polite">
          <span className="loader loader--lg" aria-hidden="true" />
          <p>Cargando tus cuestionarios...</p>
        </div>
      </div>
    );
  }
  if (activeQuiz) {
    if (activeQuiz.completed) {
      return (
        <div className="quiz-page">
          <button className="btn-back" onClick={onBack}>
            ← Volver
          </button>
          <div className="quiz-result">
            <img
              src={dinaHappyWriting}
              alt="Dina celebrando el resultado del cuestionario"
            />
            <h1>{activeQuiz.title}</h1>
            <p className="quiz-result-label">Resultado final</p>
            <strong>
              {score} / {activeQuiz.questions.length}
            </strong>
            <div className="quiz-result-actions">
              <button
                className="quiz-primary"
                onClick={() => openQuiz(activeQuiz)}
              >
                Reintentar
              </button>
              <button className="quiz-secondary" onClick={returnToQuizList}>
                Ver mis cuestionarios
              </button>
            </div>
          </div>
        </div>
      );
    }
    return (
      <div className="quiz-page quiz-page--take">
        <button className="btn-back" onClick={leaveActiveQuiz}>
          ← Volver
        </button>
        <button
          type="button"
          className="quiz-secondary"
          onClick={returnToQuizList}
        >
          ← Ver mis cuestionarios
        </button>
        <header className="quiz-take-header">
          <h1>{activeQuiz.title}</h1>
          <p>
            {subject.name} · {questionIndex + 1} / {activeQuiz.questions.length}
          </p>
        </header>
        <div className="quiz-progress">
          <span
            style={{
              width: `${((questionIndex + 1) / activeQuiz.questions.length) * 100}%`,
            }}
          />
        </div>
        <section className="quiz-question">
          <h2>{currentQuestion.question}</h2>
          <div className="quiz-options">
            {currentQuestion.options.map((option) => (
              <button
                type="button"
                key={option.id}
                className={`quiz-option ${selectedOption ? (option.id === currentQuestion.correctOptionId ? "correct" : option.id === selectedOption ? "incorrect" : "") : ""}`}
                onClick={() => answer(option.id)}
                disabled={Boolean(selectedOption)}
              >
                {option.text}
              </button>
            ))}
          </div>
          {selectedOption && (
            <div className="quiz-explanation">
              <strong>
                {selectedOption === currentQuestion.correctOptionId
                  ? "¡Correcto!"
                  : "Respuesta incorrecta"}
              </strong>
              <p>{currentQuestion.explanation}</p>
            </div>
          )}
          <button
            className="quiz-primary"
            disabled={!selectedOption}
            onClick={nextQuestion}
          >
            {questionIndex + 1 === activeQuiz.questions.length
              ? "Finalizar"
              : "Siguiente"}
          </button>
        </section>
      </div>
    );
  }

  if (!showGenerator) {
    const hasNoDocuments = files.length === 0;
    // Un cuestionario en curso ya ocupa la lista: mostrar "todavía no hay
    // cuestionarios" al lado de una fila generándose sería contradictorio.
    const hasNoQuizzes = quizzes.length === 0 && pendingQuizzes.length === 0;
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
              Nuevo cuestionario
            </button>
          </div>
        {hasNoQuizzes && (
          <div className="quiz-empty">
            <img src={dinaWritingForm} alt="Dina preparando un cuestionario" />
            <h2>Todavía no hay cuestionarios</h2>
            <p>
              {hasNoDocuments
                ? "Para crear cuestionarios, primero tenés que cargar documentos en esta materia."
                : "Creá uno a partir de los documentos de esta materia."}
            </p>
          </div>
        )}
        <div className="library-list">
          {pendingQuizzes.map((pending) => (
            <div
              className="library-item library-item--pending"
              key={pending.id}
              aria-busy="true"
            >
              <div className="library-item-pending">
                <span className="library-item-pending-title">
                  {pending.title}
                </span>
                <span className="library-item-pending-status">
                  <span className="loader" aria-hidden="true" />
                  Dina está preparando tu cuestionario...
                </span>
              </div>
            </div>
          ))}
          {quizzes.map((quiz) => (
            <div className="library-item" key={quiz.id}>
              {editingQuizId === quiz.id ? (
                <div className="quiz-set-edit">
                  <input
                    aria-label={`Nombre de ${quiz.title}`}
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
                      onClick={() => setEditingQuizId(null)}
                      disabled={savingTitle}
                    >
                      Cancelar
                    </button>
                  </div>
                </div>
              ) : confirmingDeleteId === quiz.id ? (
                <div className="quiz-set-edit">
                  <span>
                    ¿Eliminar “{quiz.title}”? Esta acción no se puede deshacer.
                  </span>
                  <div>
                    <button
                      type="button"
                      onClick={() => deleteQuiz(quiz.id)}
                      disabled={deletingQuizId === quiz.id}
                    >
                      {deletingQuizId === quiz.id
                        ? "Eliminando..."
                        : "Confirmar"}
                    </button>
                    <button
                      type="button"
                      onClick={() => setConfirmingDeleteId(null)}
                      disabled={deletingQuizId === quiz.id}
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
                    onClick={() => openQuiz(quiz)}
                  >
                    <strong>{quiz.title}</strong>
                    <span>
                      {Array.isArray(quiz.questions)
                        ? quiz.questions.length
                        : 0}{" "}
                      preguntas -{" "}
                      {difficultyLabels[quiz.difficulty] || "Básico"}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="quiz-set-rename"
                    onClick={() => startEditingTitle(quiz)}
                  >
                    Editar nombre
                  </button>
                  <button
                    type="button"
                    className="quiz-set-delete"
                    onClick={() => setConfirmingDeleteId(quiz.id)}
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
        {toast &&
          createPortal(
            <div className="library-toast" role="alert">
              {toast}
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
      <button
        className="quiz-secondary"
        onClick={() => setShowGenerator(false)}
      >
        ← Ver mis cuestionarios
      </button>
      <div className="quiz-generator">
        <div className="quiz-generator-intro">
          <img src={dinaWritingForm} alt="Dina preparando un cuestionario" />
          <h1>Generá tu cuestionario</h1>
          <p>Elegí los documentos de {subject.name} que querés usar.</p>
        </div>
        <div className="quiz-form">
          <label>Documentos</label>
          <div className="quiz-files">
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
          <label htmlFor="quiz-title">Nombre del set</label>
          <input
            id="quiz-title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Cuestionario #1"
            maxLength={160}
          />
          <label htmlFor="quiz-instructions">Indicaciones adicionales</label>
          <textarea
            id="quiz-instructions"
            value={instructions}
            onChange={(event) => setInstructions(event.target.value)}
            placeholder="Por ejemplo: focalizá en diferencias entre conceptos."
            rows={3}
          />
          <label htmlFor="question-count">
            Cantidad de preguntas: {questionCount}
          </label>
          <input
            id="question-count"
            type="range"
            min="5"
            max="20"
            value={questionCount}
            onChange={(event) => setQuestionCount(Number(event.target.value))}
          />
          <label>Respuestas por pregunta: {answerCount}</label>
          <div
            className="answer-count-options"
            role="radiogroup"
            aria-label="Respuestas por pregunta"
          >
            {[2, 3, 4].map((count) => (
              <button
                type="button"
                key={count}
                className={`answer-count-option ${answerCount === count ? "selected" : ""}`}
                aria-pressed={answerCount === count}
                onClick={() => setAnswerCount(count)}
              >
                {count}
              </button>
            ))}
          </div>
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
            Generar cuestionario
          </button>
        </div>
      </div>
    </div>
  );
}
