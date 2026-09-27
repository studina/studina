import { useEffect, useRef, useState } from "react";
import "./FlashcardsPage.css";
import dinaEmptyFlashcards from "../assets/dina/dina-empty-flashcards.png";
import dinaSearchingFlashcards from "../assets/dina/dina-searching-flashcards.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

const ratingLabels = [
  "No lo sabía",
  "Me sonaba, pero no pude recordarlo",
  "Lo recordé con bastante dificultad",
  "Lo sabía, pero dudé",
  "Lo sabía con seguridad",
];

export default function FlashcardsPage({ subject, user, onBack }) {
  const [cards, setCards] = useState([]);
  const [savedSets, setSavedSets] = useState([]);
  const [loadingSets, setLoadingSets] = useState(true);
  const [showGenerator, setShowGenerator] = useState(false);
  const [editingSetId, setEditingSetId] = useState(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const [deletingSetId, setDeletingSetId] = useState(null);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState(null);
  const [files, setFiles] = useState([]);
  const [selectedFileIds, setSelectedFileIds] = useState([]);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [cardCount, setCardCount] = useState(10);
  const [cardFormat, setCardFormat] = useState("term_definition");
  const [difficulty, setDifficulty] = useState("basic");
  const [loadingFiles, setLoadingFiles] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState("");
  const [index, setIndex] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [slideDir, setSlideDir] = useState(null); // 'left' | 'right' | null
  const [exiting, setExiting] = useState(false);
  const [activeSetId, setActiveSetId] = useState(null);
  const [submittingRating, setSubmittingRating] = useState(false);
  const [studySessionId, setStudySessionId] = useState(null);
  const [ratedCardIds, setRatedCardIds] = useState([]);
  const [sessionCompleted, setSessionCompleted] = useState(false);
  const [topicProgress, setTopicProgress] = useState([]);
  const [reviewMode, setReviewMode] = useState(false);
  const [recentlyShownCardIds, setRecentlyShownCardIds] = useState([]);
  const [cardAttempts, setCardAttempts] = useState([]);
  const pendingAttemptRef = useRef(null);
  const timerRef = useRef(null);
  const authorizationHeader = user?.token ? `Bearer ${user.token}` : null;

  useEffect(() => {
    if (!authorizationHeader) {
      setLoadingFiles(false);
      return undefined;
    }

    let active = true;
    setLoadingFiles(true);
    fetch(
      `/api/files/list?subjectId=${encodeURIComponent(String(subject.id))}`,
      {
        headers: { Authorization: authorizationHeader },
      },
    )
      .then(async (response) => {
        const body = await response.json().catch(() => ({}));
        if (!response.ok)
          throw new Error(body.error || "No se pudieron cargar los documentos");
        return Array.isArray(body.data) ? body.data : [];
      })
      .then((loadedFiles) => {
        if (!active) return;
        setFiles(loadedFiles);
      })
      .catch((requestError) => {
        if (active) setError(requestError.message);
      })
      .finally(() => {
        if (active) setLoadingFiles(false);
      });

    return () => {
      active = false;
    };
  }, [authorizationHeader, subject.id]);

  useEffect(() => {
    if (!authorizationHeader) return undefined;
    let active = true;
    fetch(`/api/flashcards/progress?subjectId=${encodeURIComponent(String(subject.id))}`, {
      headers: { Authorization: authorizationHeader },
    })
      .then(async (response) => {
        const body = await response.json().catch(() => []);
        if (!response.ok) throw new Error(body.error || "No se pudo cargar el progreso");
        return Array.isArray(body) ? body : [];
      })
      .then((progress) => {
        if (active) setTopicProgress(progress);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [authorizationHeader, subject.id]);

  useEffect(() => {
    if (!authorizationHeader || !activeSetId || !cards[index]?.id) {
      setCardAttempts([]);
      return undefined;
    }
    let active = true;
    fetch(`/api/flashcards/cards/${encodeURIComponent(cards[index].id)}/attempts?subjectId=${encodeURIComponent(String(subject.id))}`, {
      headers: { Authorization: authorizationHeader },
    })
      .then((response) => response.json())
      .then((attempts) => {
        if (active) setCardAttempts(Array.isArray(attempts) ? attempts : []);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [authorizationHeader, activeSetId, cards, index, subject.id]);

  const startStudySession = async (flashcardSetId) => {
    const response = await fetch(`/api/flashcards/study-sessions?subjectId=${encodeURIComponent(String(subject.id))}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: authorizationHeader,
      },
      body: JSON.stringify({ flashcardSetId }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.id) {
      throw new Error(body.error || "No se pudo iniciar la sesión de estudio");
    }
    setStudySessionId(body.id);
    setRatedCardIds([]);
    setSessionCompleted(false);
    return body.id;
  };

  const refreshTopicProgress = async () => {
    const response = await fetch(`/api/flashcards/progress?subjectId=${encodeURIComponent(String(subject.id))}`, {
      headers: { Authorization: authorizationHeader },
    });
    if (!response.ok) return;
    const progress = await response.json().catch(() => []);
    setTopicProgress(Array.isArray(progress) ? progress : []);
  };

  useEffect(() => {
    if (!authorizationHeader) {
      setLoadingSets(false);
      return undefined;
    }

    let active = true;
    setLoadingSets(true);
    fetch(
      `/api/flashcards?subjectId=${encodeURIComponent(String(subject.id))}`,
      {
        headers: { Authorization: authorizationHeader },
      },
    )
      .then(async (response) => {
        const body = await response.json().catch(() => ({}));
        if (!response.ok)
          throw new Error(body.error || "No se pudieron cargar las flashcards");
        return Array.isArray(body) ? body : [];
      })
      .then((sets) => {
        if (active) {
          setSavedSets(
            [...sets].sort(
              (firstSet, secondSet) =>
                new Date(secondSet.date_created || 0) -
                new Date(firstSet.date_created || 0),
            ),
          );
        }
      })
      .catch((requestError) => {
        if (active) setError(requestError.message);
      })
      .finally(() => {
        if (active) setLoadingSets(false);
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
      const response = await fetch("/api/ai/files/flashcards", {
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
          cardCount,
          cardFormat,
          difficulty,
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(body.error || "No se pudieron generar las flashcards");
      if (!Array.isArray(body.cards) || body.cards.length === 0) {
        throw new Error(
          "La IA no devolvió flashcards para los documentos seleccionados",
        );
      }

      setCards(body.cards);
      setSavedSets((previousSets) => [body, ...previousSets]);
      setActiveSetId(body.id || null);
      setIndex(0);
      setFlipped(false);
      setReviewMode(false);
      setRecentlyShownCardIds([]);
      if (body.id) await startStudySession(body.id);
    } catch (requestError) {
      console.error("Error generando flashcards:", requestError);
      setError(requestError.message);
    } finally {
      setGenerating(false);
    }
  };

  const selectWeightedReviewCard = async (flashcardSetId, recentIds) => {
    const query = new URLSearchParams({
      subjectId: String(subject.id),
      flashcardSetId,
    });
    recentIds.forEach((cardId) => query.append("recentlyShownCardIds", cardId));
    const response = await fetch(`/api/flashcards/review/next?${query}`, {
      headers: { Authorization: authorizationHeader },
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || "No se pudo seleccionar una tarjeta de repaso");
    return body.card || null;
  };

  const openSet = async (flashcardSet, weightedReview = false) => {
    if (!Array.isArray(flashcardSet.cards) || flashcardSet.cards.length === 0)
      return;
    try {
      setError("");
      setActiveSetId(flashcardSet.id);
      await startStudySession(flashcardSet.id);
      setCards(flashcardSet.cards);
      setReviewMode(weightedReview);
      setRecentlyShownCardIds([]);
      setIndex(0);
      setFlipped(false);
      if (weightedReview) {
        const selected = await selectWeightedReviewCard(flashcardSet.id, []);
        const selectedIndex = flashcardSet.cards.findIndex((card) => card.id === selected?.id);
        if (selectedIndex >= 0) setIndex(selectedIndex);
      }
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  const startEditingTitle = (flashcardSet) => {
    setEditingSetId(flashcardSet.id);
    setEditingTitle(flashcardSet.title || "");
    setError("");
  };

  const saveTitle = async () => {
    const trimmedTitle = editingTitle.trim();
    if (!trimmedTitle || !editingSetId || savingTitle) return;

    setSavingTitle(true);
    setError("");
    try {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(editingSetId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
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
          body.error || "No se pudo actualizar el nombre del set",
        );

      setSavedSets((previousSets) =>
        previousSets.map((flashcardSet) =>
          flashcardSet.id === editingSetId
            ? { ...flashcardSet, ...body }
            : flashcardSet,
        ),
      );
      setEditingSetId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSavingTitle(false);
    }
  };

  const deleteSet = async (flashcardSetId) => {
    if (!authorizationHeader || deletingSetId) return;

    setDeletingSetId(flashcardSetId);
    setError("");
    try {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(flashcardSetId)}?subjectId=${encodeURIComponent(String(subject.id))}`,
        { method: "DELETE", headers: { Authorization: authorizationHeader } },
      );
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(
          body.error || "No se pudo eliminar el set de flashcards",
        );
      }

      setSavedSets((previousSets) =>
        previousSets.filter(
          (flashcardSet) => flashcardSet.id !== flashcardSetId,
        ),
      );
      setConfirmingDeleteId(null);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setDeletingSetId(null);
    }
  };

  if (loadingSets && !showGenerator) {
    return (
      <div className="fc-page fc-page--loading">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="fc-loading" role="status">
          <img
            src={dinaSearchingFlashcards}
            alt="Dina buscando tus sets de flashcards"
          />
          <p>Cargando tus sets...</p>
        </div>
      </div>
    );
  }

  if (cards.length === 0 && !showGenerator) {
    const hasNoDocuments = !loadingFiles && files.length === 0;
    const hasNoSavedSets = !loadingSets && savedSets.length === 0;

    return (
      <div className="fc-page">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="fc-header">
          <h1 className="fc-title">Flashcards</h1>
          <p className="fc-sub">{subject.name}</p>
        </div>
        <div className="fc-library-header">
          <h2>Mis sets</h2>
          <button
            type="button"
            className="fc-generate-btn"
            onClick={() => setShowGenerator(true)}
            disabled={hasNoDocuments}
          >
            Nuevo set
          </button>
        </div>
        {topicProgress.length > 0 && (
          <section className="fc-topic-progress" aria-label="Progreso por tema">
            <h2>Progreso por tema</h2>
            {topicProgress.map((progress) => (
              <div className="fc-topic-progress-item" key={progress.topic_id}>
                <div className="fc-topic-progress-label">
                  <span>{progress.topic_name || "Tema"}</span>
                  <span>{Math.round((progress.coverage || 0) * 100)}% recorrido · {Math.round((progress.mastery_score || 0) * 100)}% mastery</span>
                </div>
                <div className="fc-progress-bar">
                  <div className="fc-progress-fill" style={{ width: `${Math.round((progress.coverage || 0) * 100)}%` }} />
                </div>
                <small>{progress.unique_resources_seen} de {progress.total_resources} recursos · {progress.total_attempts} intentos</small>
              </div>
            ))}
          </section>
        )}
        {hasNoSavedSets && (
          <div className="fc-empty">
            <img src={dinaEmptyFlashcards} alt="Dina con tarjetas de estudio" />
            <h2>Todavía no hay sets</h2>
            <p>
              {hasNoDocuments
                ? "Para crear flashcards, primero tenés que cargar documentos en esta materia."
                : "Creá uno a partir de los documentos de esta materia."}
            </p>
          </div>
        )}
        <div className="fc-set-list">
          {savedSets.map((flashcardSet) => (
            <div className="fc-set-item" key={flashcardSet.id}>
              {editingSetId === flashcardSet.id ? (
                <div className="fc-set-edit">
                  <input
                    aria-label={`Nombre de ${flashcardSet.title}`}
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
              ) : confirmingDeleteId === flashcardSet.id ? (
                <div className="fc-set-edit">
                  <span>
                    ¿Eliminar “{flashcardSet.title}”? Esta acción no se puede
                    deshacer.
                  </span>
                  <div>
                    <button
                      type="button"
                      onClick={() => deleteSet(flashcardSet.id)}
                      disabled={deletingSetId === flashcardSet.id}
                    >
                      {deletingSetId === flashcardSet.id
                        ? "Eliminando..."
                        : "Confirmar"}
                    </button>
                    <button
                      type="button"
                      onClick={() => setConfirmingDeleteId(null)}
                      disabled={deletingSetId === flashcardSet.id}
                    >
                      Cancelar
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  <button
                    type="button"
                    className="fc-set-open"
                    onClick={() => openSet(flashcardSet)}
                  >
                    <span className="fc-set-title">{flashcardSet.title}</span>
                    <span className="fc-set-meta">
                      {Array.isArray(flashcardSet.cards)
                        ? flashcardSet.cards.length
                        : 0}{" "}
                      tarjetas -{" "}
                      {difficultyLabels[flashcardSet.difficulty] || "Básico"}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="fc-set-rename"
                    onClick={() => openSet(flashcardSet, true)}
                  >
                    Repaso ponderado
                  </button>
                  <button
                    type="button"
                    className="fc-set-rename"
                    onClick={() => startEditingTitle(flashcardSet)}
                  >
                    Editar nombre
                  </button>
                  <button
                    type="button"
                    className="fc-set-delete"
                    onClick={() => setConfirmingDeleteId(flashcardSet.id)}
                  >
                    Eliminar
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
        {error && <p className="fc-generator-error">{error}</p>}
      </div>
    );
  }

  if (cards.length === 0) {
    return (
      <div className="fc-page">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <button
          type="button"
          className="fc-cancel-generator"
          onClick={() => setShowGenerator(false)}
        >
          ← Ver mis sets
        </button>
        <div className="fc-generator">
          <div className="fc-empty">
            <img src={dinaEmptyFlashcards} alt="Dina con tarjetas de estudio" />
            <h2>Generá tus flashcards</h2>
            <p>Elegí los documentos de {subject.name} que querés repasar.</p>
          </div>
          <div className="fc-generator-form">
            <p className="fc-generator-label">Documentos</p>
            {loadingFiles && (
              <p className="fc-generator-status">Cargando documentos...</p>
            )}
            {!loadingFiles && files.length === 0 && (
              <p className="fc-generator-status">
                Todavía no hay documentos cargados para esta materia.
              </p>
            )}
            <div className="fc-file-list">
              {files.map((file) => (
                <label className="fc-file-option" key={file.id}>
                  <input
                    type="checkbox"
                    checked={selectedFileIds.includes(file.id)}
                    onChange={() => toggleFile(file.id)}
                  />
                  <span>
                    {file.filename || file.name || "Documento sin nombre"}
                  </span>
                </label>
              ))}
            </div>
            <label className="fc-generator-label" htmlFor="flashcard-title">
              Nombre del set
            </label>
            <input
              id="flashcard-title"
              className="fc-instructions"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Flashcards #1"
              maxLength={160}
            />
            <label
              className="fc-generator-label"
              htmlFor="flashcard-instructions"
            >
              Indicaciones adicionales
            </label>
            <textarea
              id="flashcard-instructions"
              className="fc-instructions"
              value={instructions}
              onChange={(event) => setInstructions(event.target.value)}
              placeholder="Por ejemplo: focalizá en definiciones y diferencias entre conceptos."
              rows={3}
            />
            <label className="fc-generator-label" htmlFor="flashcard-count">
              Cantidad de flashcards: {cardCount}
            </label>
            <input
              id="flashcard-count"
              type="range"
              className="fc-count-slider"
              min={5}
              max={20}
              value={cardCount}
              onChange={(event) => setCardCount(Number(event.target.value))}
            />
            <label className="fc-generator-label">
              Formato de tarjeta:{" "}
              {cardFormat === "term_definition"
                ? "Término / Definición"
                : "Pregunta / Respuesta"}
            </label>
            <div
              className="fc-difficulty-options"
              role="radiogroup"
              aria-label="Formato de tarjeta"
            >
              <button
                type="button"
                className={`fc-difficulty-option ${cardFormat === "term_definition" ? "selected" : ""}`}
                aria-pressed={cardFormat === "term_definition"}
                onClick={() => setCardFormat("term_definition")}
              >
                Término / Definición
              </button>
              <button
                type="button"
                className={`fc-difficulty-option ${cardFormat === "question_answer" ? "selected" : ""}`}
                aria-pressed={cardFormat === "question_answer"}
                onClick={() => setCardFormat("question_answer")}
              >
                Pregunta / Respuesta
              </button>
            </div>
            <label className="fc-generator-label">
              Dificultad: {difficultyLabels[difficulty]}
            </label>
            <div
              className="fc-difficulty-options"
              role="radiogroup"
              aria-label="Dificultad"
            >
              {Object.entries(difficultyLabels).map(([value, label]) => (
                <button
                  type="button"
                  key={value}
                  className={`fc-difficulty-option ${difficulty === value ? "selected" : ""}`}
                  aria-pressed={difficulty === value}
                  onClick={() => setDifficulty(value)}
                >
                  {label}
                </button>
              ))}
            </div>
            {generating && (
              <div
                className="fc-generation-status"
                role="status"
                aria-live="polite"
              >
                <span className="fc-loader" aria-hidden="true" />
                <span>Dina está preparando tus flashcards...</span>
              </div>
            )}
            {error && <p className="fc-generator-error">{error}</p>}
            <button
              type="button"
              className="fc-generate-btn"
              onClick={generate}
              disabled={
                loadingFiles || generating || selectedFileIds.length === 0
              }
            >
              {generating ? "Generando..." : "Generar flashcards"}
            </button>
          </div>
        </div>
      </div>
    );
  }

  const current = cards[index];

  const navigate = (dir, afterRating = false) => {
    if (exiting || submittingRating || sessionCompleted) return;
    if (dir === "left" && !afterRating && !ratedCardIds.includes(current?.id)) return;
    clearTimeout(timerRef.current);
    setSlideDir(dir);
    setExiting(true);
    timerRef.current = setTimeout(() => {
      setFlipped(false);
      setIndex((i) =>
        dir === "left"
          ? Math.min(i + 1, cards.length - 1)
          : Math.max(i - 1, 0),
      );
      setExiting(false);
    }, 260);
  };

  const goNext = () => navigate("left");
  const goPrev = () => navigate("right");
  const handleFlip = () => {
    if (!exiting && !submittingRating) setFlipped((f) => !f);
  };

  const rateCurrentCard = async (rating) => {
    if (
      !authorizationHeader ||
      !activeSetId ||
      !current?.id ||
      submittingRating
    ) {
      return;
    }
    if (pendingAttemptRef.current?.cardId === current.id
        && pendingAttemptRef.current.rating !== rating) {
      return;
    }

    setSubmittingRating(true);
    setError("");
    try {
      if (!pendingAttemptRef.current || pendingAttemptRef.current.cardId !== current.id) {
        pendingAttemptRef.current = {
          cardId: current.id,
          rating,
          idempotencyKey: globalThis.crypto?.randomUUID?.()
            || "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
              const random = Math.random() * 16 | 0;
              return (character === "x" ? random : (random & 0x3 | 0x8)).toString(16);
            }),
        };
      }
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(activeSetId)}/attempts?subjectId=${encodeURIComponent(String(subject.id))}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: authorizationHeader,
          },
          body: JSON.stringify({
            cardId: current.id,
            rating,
            sessionId: studySessionId,
            idempotencyKey: pendingAttemptRef.current.idempotencyKey,
          }),
        },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(body.error || "No se pudo guardar la autoevaluación");
      }
      await refreshTopicProgress();
      setRatedCardIds((previous) => previous.includes(current.id) ? previous : [...previous, current.id]);
      if (reviewMode) {
        const recent = [...recentlyShownCardIds, current.id].slice(-3);
        setRecentlyShownCardIds(recent);
        const nextCard = await selectWeightedReviewCard(activeSetId, recent);
        const nextIndex = cards.findIndex((card) => card.id === nextCard?.id);
        if (nextIndex >= 0) {
          setIndex(nextIndex);
          setFlipped(false);
        }
        pendingAttemptRef.current = null;
      } else if (index === cards.length - 1) {
        const completion = await fetch(`/api/flashcards/study-sessions/${encodeURIComponent(studySessionId)}/complete?subjectId=${encodeURIComponent(String(subject.id))}`, {
          method: "POST",
          headers: { Authorization: authorizationHeader },
        });
        const completionBody = await completion.json().catch(() => ({}));
        if (!completion.ok) throw new Error(completionBody.error || "No se pudo finalizar la sesión");
        setSessionCompleted(true);
        pendingAttemptRef.current = null;
      } else {
        pendingAttemptRef.current = null;
        goNextAfterRating();
      }
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setSubmittingRating(false);
    }
  };

  const goNextAfterRating = () => navigate("left", true);

  const slideClass = exiting
    ? `fc-slide-exit-${slideDir}`
    : slideDir
      ? `fc-slide-enter-${slideDir}`
      : "";

  const finishReviewSession = async () => {
    if (!studySessionId || submittingRating) return;
    try {
      const response = await fetch(`/api/flashcards/study-sessions/${encodeURIComponent(studySessionId)}/complete?subjectId=${encodeURIComponent(String(subject.id))}`, {
        method: "POST",
        headers: { Authorization: authorizationHeader },
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || "No se pudo finalizar la sesión");
      await refreshTopicProgress();
      setSessionCompleted(true);
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  const abandonStudySession = async () => {
    if (!studySessionId || sessionCompleted) return true;
    try {
      const response = await fetch(`/api/flashcards/study-sessions/${encodeURIComponent(studySessionId)}/abandon?subjectId=${encodeURIComponent(String(subject.id))}`, {
        method: "POST",
        headers: { Authorization: authorizationHeader },
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || "No se pudo cerrar la sesión");
      setStudySessionId(null);
      return true;
    } catch (requestError) {
      setError(requestError.message);
      return false;
    }
  };

  const leaveStudy = async () => {
    if (await abandonStudySession()) onBack();
  };

  const returnToLibrary = async () => {
    if (!(await abandonStudySession())) return;
    setCards([]);
    setActiveSetId(null);
    setReviewMode(false);
    setRecentlyShownCardIds([]);
  };

  if (sessionCompleted) {
    return (
      <div className="fc-page">
        <div className="fc-header">
          <h1 className="fc-title">Sesión completada</h1>
          <p className="fc-sub">Guardamos tus intentos y actualizamos el progreso por tema.</p>
        </div>
        <button type="button" className="fc-generate-btn" onClick={() => {
          setCards([]);
          setActiveSetId(null);
          setStudySessionId(null);
          setSessionCompleted(false);
          setReviewMode(false);
        }}>
          Volver a mis sets
        </button>
      </div>
    );
  }

  return (
    <div className="fc-page">
      <button className="btn-back" onClick={leaveStudy}>
        ← Volver
      </button>
      <button
        type="button"
        className="fc-cancel-generator"
        onClick={returnToLibrary}
      >
        ← Ver mis sets
      </button>

      <div className="fc-header">
        <h1 className="fc-title">Flashcards</h1>
        <p className="fc-sub">{subject.name}</p>
      </div>

      {/* ── Progreso ── */}
      <div className="fc-progress-row">
        <span className="fc-counter">
          {reviewMode ? "Repaso ponderado" : `${index + 1} / ${cards.length}`}
        </span>
        {!reviewMode && <div className="fc-progress-bar">
          <div
            className="fc-progress-fill"
            style={{ width: `${((index + 1) / cards.length) * 100}%` }}
          />
        </div>}
      </div>

      {/* ── Tarjeta ──
			    fc-scene-wrapper: limita el ancho y clipea el slide (no el flip)
			    fc-slide:         maneja translateX (slide entre cards)
			    fc-card:          maneja rotateY (flip), tiene preserve-3d
			-->*/}
      <div className={`fc-scene-wrapper ${exiting ? "is-sliding" : ""}`}>
        <div className="fc-scene" onClick={handleFlip}>
          {/* El slide va en este div, separado del flip */}
          <div key={index} className={`fc-slide ${slideClass}`}>
            {/* El flip va acá, sin interferencia del translateX */}
            <div className={`fc-card ${flipped ? "flipped" : ""}`}>
              <div className="fc-face fc-front">
                <span className="fc-face-label">Término</span>
                <p className="fc-term">{current.term}</p>
                <span className="fc-hint">Tocá para ver la definición</span>
              </div>
              <div className="fc-face fc-back">
                <span className="fc-face-label">Definición</span>
                <p className="fc-definition">{current.definition}</p>
                <span className="fc-hint">Tocá para volver al término</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ── Navegación ── */}
      <div className="fc-nav">
        {!reviewMode && <button
          className="fc-nav-btn"
          onClick={goPrev}
          disabled={index === 0 || submittingRating}
        >← Anterior</button>}
        <button className="fc-nav-btn fc-nav-btn--flip" onClick={handleFlip}>
          {flipped ? "🔄 Ver término" : "🔄 Ver definición"}
        </button>
        {!reviewMode && <button
          className="fc-nav-btn"
          onClick={goNext}
          disabled={cards.length <= 1 || submittingRating || !ratedCardIds.includes(current?.id)}
        >Siguiente →</button>}
        {reviewMode && <button className="fc-nav-btn" onClick={finishReviewSession} disabled={submittingRating}>Terminar repaso</button>}
      </div>
      {flipped && current?.id && activeSetId && (
        <div className="fc-rating" role="group" aria-label="Autoevaluación">
          <span className="fc-rating-title">¿Qué tan bien la sabías?</span>
          <div className="fc-rating-options">
            {ratingLabels.map((label, rating) => (
              <button
                type="button"
                key={label}
                className="fc-rating-option"
                onClick={() => rateCurrentCard(rating)}
                disabled={submittingRating || (pendingAttemptRef.current?.cardId === current.id
                  && pendingAttemptRef.current.rating !== rating)}
              >
                {label}
              </button>
            ))}
          </div>
          {submittingRating && <span role="status">Guardando...</span>}
        </div>
      )}
      {cardAttempts.length > 0 && (
        <details className="fc-attempt-history">
          <summary>Historial de esta tarjeta ({cardAttempts.length})</summary>
          <ul>
            {cardAttempts.map((attempt) => (
              <li key={attempt.id}>Rating {attempt.rating} · {new Date(attempt.created_at).toLocaleString()}</li>
            ))}
          </ul>
        </details>
      )}
      {error && <p className="fc-generator-error">{error}</p>}
    </div>
  );
}
