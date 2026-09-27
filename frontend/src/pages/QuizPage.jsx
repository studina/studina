import { useEffect, useRef, useState } from "react";
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
  const [loading, setLoading] = useState(true);
  const [showGenerator, setShowGenerator] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState("");
  const [editingQuizId, setEditingQuizId] = useState(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState(null);
  const [deletingQuizId, setDeletingQuizId] = useState(null);
  const [activeQuiz, setActiveQuiz] = useState(null);
  const [questionIndex, setQuestionIndex] = useState(0);
  const [selectedOption, setSelectedOption] = useState(null);
  const [score, setScore] = useState(0);
  const [studySessionId, setStudySessionId] = useState(null);
  const [savingAnswer, setSavingAnswer] = useState(false);
  const [answerError, setAnswerError] = useState("");
  const pendingAnswerRef = useRef(null);

  useEffect(() => {
    if (!authorizationHeader) {
      setLoading(false);
      return undefined;
    }
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

  const toggleFile = (fileId) => {
    setSelectedFileIds((previousIds) =>
      previousIds.includes(fileId)
        ? previousIds.filter((id) => id !== fileId)
        : [...previousIds, fileId],
    );
  };

  const generate = async () => {
    if (!authorizationHeader || selectedFileIds.length === 0 || generating)
      return;
    setGenerating(true);
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
      setQuizzes((previousQuizzes) => [body, ...previousQuizzes]);
      openQuiz(body);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setGenerating(false);
    }
  };

  const openQuiz = async (quiz) => {
    if (!Array.isArray(quiz.questions) || quiz.questions.length === 0) return;
    const response = await fetch(`/api/quizzes/${encodeURIComponent(quiz.id)}/study-sessions?subjectId=${encodeURIComponent(String(subject.id))}`, {
      method: "POST",
      headers: { Authorization: authorizationHeader },
    });
    const session = await response.json().catch(() => ({}));
    if (!response.ok || !session.id) {
      setError(session.error || "No se pudo iniciar la sesión del cuestionario");
      return;
    }
    const { completed, ...quizToOpen } = quiz;
    setActiveQuiz(quizToOpen);
    setStudySessionId(session.id);
    setQuestionIndex(0);
    setSelectedOption(null);
    setScore(0);
    setAnswerError("");
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
        `/api/quizzes/${encodeURIComponent(editingQuizId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
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
        `/api/quizzes/${encodeURIComponent(quizId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
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
  const answer = async (optionId) => {
    if (selectedOption || savingAnswer || !currentQuestion?.id || !activeQuiz?.id) return;
    setSavingAnswer(true);
    setAnswerError("");
    if (!pendingAnswerRef.current || pendingAnswerRef.current.questionId !== currentQuestion.id) {
      pendingAnswerRef.current = {
        questionId: currentQuestion.id,
        optionId,
        idempotencyKey: globalThis.crypto?.randomUUID?.()
          || "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
            const random = Math.random() * 16 | 0;
            return (character === "x" ? random : (random & 0x3 | 0x8)).toString(16);
          }),
      };
    }
    if (pendingAnswerRef.current.optionId !== optionId) {
      setSavingAnswer(false);
      return;
    }
    try {
      const response = await fetch(`/api/quizzes/${encodeURIComponent(activeQuiz.id)}/attempts?subjectId=${encodeURIComponent(String(subject.id))}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: authorizationHeader,
        },
        body: JSON.stringify({
          questionId: currentQuestion.id,
          selectedOptionId: optionId,
          sessionId: studySessionId,
          idempotencyKey: pendingAnswerRef.current.idempotencyKey,
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || "No se pudo guardar la respuesta");
      pendingAnswerRef.current = null;
      setSelectedOption(optionId);
      if (optionId === currentQuestion.correctOptionId)
        setScore((previousScore) => previousScore + 1);
    } catch (requestError) {
      setAnswerError(requestError.message);
    } finally {
      setSavingAnswer(false);
    }
  };
  const nextQuestion = async () => {
    if (!selectedOption) return;
    if (questionIndex + 1 >= activeQuiz.questions.length) {
      const response = await fetch(`/api/flashcards/study-sessions/${encodeURIComponent(studySessionId)}/complete?subjectId=${encodeURIComponent(String(subject.id))}`, {
        method: "POST",
        headers: { Authorization: authorizationHeader },
      });
      if (!response.ok) {
        setAnswerError("No se pudo completar la sesión");
        return;
      }
      setActiveQuiz({ ...activeQuiz, completed: true });
      return;
    }
    setQuestionIndex((previousIndex) => previousIndex + 1);
    setSelectedOption(null);
    setAnswerError("");
  };

  const leaveActiveQuiz = async () => {
    if (studySessionId && activeQuiz && !activeQuiz.completed) {
      const response = await fetch(`/api/flashcards/study-sessions/${encodeURIComponent(studySessionId)}/abandon?subjectId=${encodeURIComponent(String(subject.id))}`, {
        method: "POST",
        headers: { Authorization: authorizationHeader },
      });
      if (!response.ok) {
        setAnswerError("No se pudo cerrar la sesión del cuestionario");
        return;
      }
    }
    onBack();
  };

  if (loading) {
    return (
      <div className="fc-page fc-page--loading">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="fc-loading" role="status">
          <img src={dinaWritingForm} alt="Dina buscando tus cuestionarios" />
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
              <button
                className="quiz-secondary"
                onClick={() => setActiveQuiz(null)}
              >
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
                disabled={savingAnswer || Boolean(selectedOption)
                  || (pendingAnswerRef.current?.questionId === currentQuestion.id
                    && pendingAnswerRef.current.optionId !== option.id)}
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
          {savingAnswer && <p role="status">Guardando respuesta...</p>}
          {answerError && <p className="quiz-error" role="alert">{answerError}</p>}
          <button
            className="quiz-primary"
            disabled={!selectedOption || savingAnswer}
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
    return (
      <div className="quiz-page">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <header>
          <h1>Cuestionarios</h1>
          <p>{subject.name}</p>
        </header>
        <div className="quiz-library-header">
          <h2>Mis cuestionarios</h2>
          <button
            className="quiz-primary"
            disabled={hasNoDocuments}
            onClick={() => setShowGenerator(true)}
          >
            Nuevo cuestionario
          </button>
        </div>
        {quizzes.length === 0 && (
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
        <div className="quiz-list">
          {quizzes.map((quiz) => (
            <div className="quiz-set" key={quiz.id}>
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
        {error && <p className="quiz-error">{error}</p>}
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
          {generating && (
            <div
              className="quiz-generation-status"
              role="status"
              aria-live="polite"
            >
              <span className="quiz-loader" aria-hidden="true" />
              <span>Dina está preparando tu cuestionario...</span>
            </div>
          )}
          {error && <p className="quiz-error">{error}</p>}
          <button
            className="quiz-primary"
            disabled={generating || selectedFileIds.length === 0}
            onClick={generate}
          >
            {generating ? "Generando..." : "Generar cuestionario"}
          </button>
        </div>
      </div>
    </div>
  );
}
