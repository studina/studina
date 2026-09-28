import { useEffect, useRef, useState } from "react";
import { randomUuid } from "../lib/uuid";
import { openFileInNewTab } from "../lib/openFile";
import "./FlashcardsPage.css";
import dinaEmptyFlashcards from "../assets/dina/dina-empty-flashcards.png";
import dinaSearchingFlashcards from "../assets/dina/dina-searching-flashcards.png";
import dinaHappyWriting from "../assets/dina/dina-happy-writing.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

/** Por debajo de esto, una tarjeta entra en la ronda de refuerzo. */
const MASTERY_REVIEW_THRESHOLD = 0.6;

const percent = (mastery) => Math.round((Number(mastery) || 0) * 100);

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
  const [activeSet, setActiveSet] = useState(null);
  const [ratedCardIds, setRatedCardIds] = useState([]);
  const [sessionCompleted, setSessionCompleted] = useState(false);
  const [reviewMode, setReviewMode] = useState(false);
  const [recentlyShownCardIds, setRecentlyShownCardIds] = useState([]);
  const [summary, setSummary] = useState(null);
  const [summaryError, setSummaryError] = useState("");
  const [writesFailed, setWritesFailed] = useState(false);
  const [roundNumber, setRoundNumber] = useState(1);
  const [startingRound, setStartingRound] = useState(false);
  const [openingSetId, setOpeningSetId] = useState(null);
  const [selectingNextCard, setSelectingNextCard] = useState(false);
  const pendingAttemptRef = useRef(null);
  const pendingWritesRef = useRef(Promise.resolve());
  const timerRef = useRef(null);
  const objectUrlsRef = useRef([]);
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

  const resetStudyRun = () => {
    setRatedCardIds([]);
    setSessionCompleted(false);
    setSummary(null);
    setSummaryError("");
    setWritesFailed(false);
    setRoundNumber(1);
  };

  // Los blobs de los documentos viven mientras la pestaña que los muestra siga abierta,
  // así que recién se liberan cuando esta pantalla se desmonta.
  useEffect(
    () => () => {
      objectUrlsRef.current.forEach((url) => URL.revokeObjectURL(url));
      objectUrlsRef.current = [];
    },
    [],
  );

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

  // Al terminar una ronda, resumimos el progreso de los topics que toca este set.
  useEffect(() => {
    if (!sessionCompleted || !activeSet || !authorizationHeader) return undefined;

    let active = true;

    const load = async () => {
      // Los ratings se mandan fire-and-forget: sin esperarlos, el progreso que
      // devuelve la API es el de antes de esta ronda. El backend recalcula
      // user_topic_progress dentro del POST del intento, así que cuando la cola
      // drena el agregado ya está fresco.
      await pendingWritesRef.current;
      const response = await fetch(
        `/api/flashcards/progress?subjectId=${encodeURIComponent(String(subject.id))}`,
        { headers: { Authorization: authorizationHeader } },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(body.error || "No se pudo cargar tu progreso");

      const setTopicIds = new Set(
        (activeSet.cards || []).map((card) => card.topic_id).filter(Boolean),
      );
      const topics = (Array.isArray(body) ? body : [])
        .filter((row) => setTopicIds.has(row.topic_id))
        .map((row) => ({
          topicId: row.topic_id,
          name: row.topic_name || "Tema sin nombre",
          mastery: Number(row.mastery_score) || 0,
          coverage: Number(row.coverage) || 0,
        }));

      const averageMastery = topics.length
        ? topics.reduce((total, topic) => total + topic.mastery, 0) / topics.length
        : 0;
      const weakest = topics.length
        ? [...topics].sort(
            (first, second) =>
              first.mastery - second.mastery ||
              first.coverage - second.coverage ||
              first.name.localeCompare(second.name),
          )[0]
        : null;

      return { topics, averageMastery, weakest };
    };

    load()
      .then((result) => {
        if (active) setSummary(result);
      })
      .catch((requestError) => {
        if (active) setSummaryError(requestError.message);
      });

    return () => {
      active = false;
    };
  }, [sessionCompleted, activeSet, authorizationHeader, subject.id, roundNumber]);

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
      setActiveSet(body);
      setIndex(0);
      setFlipped(false);
      setReviewMode(false);
      setRecentlyShownCardIds([]);
      if (body.id) resetStudyRun();
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
    if (openingSetId) return;
    try {
      setError("");
      setOpeningSetId(flashcardSet.id);

      // La selección ponderada se resuelve antes de montar la vista de estudio.
      // Si se montara primero, se vería la tarjeta 0 del set y recién al volver
      // el fetch saltaría a la sorteada.
      let startIndex = 0;
      if (weightedReview) {
        const selected = await selectWeightedReviewCard(flashcardSet.id, []);
        const selectedIndex = flashcardSet.cards.findIndex(
          (card) => card.id === selected?.id,
        );
        if (selectedIndex >= 0) startIndex = selectedIndex;
      }

      setActiveSetId(flashcardSet.id);
      setActiveSet(flashcardSet);
      resetStudyRun();
      setCards(flashcardSet.cards);
      setReviewMode(weightedReview);
      setRecentlyShownCardIds([]);
      setIndex(startIndex);
      setFlipped(false);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setOpeningSetId(null);
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
                    disabled={Boolean(openingSetId)}
                  >
                    {openingSetId === flashcardSet.id
                      ? "Preparando..."
                      : "Repaso ponderado"}
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
    if (exiting || sessionCompleted) return;
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
      pendingAttemptRef.current = null;
    }, 260);
  };

  const goNext = () => navigate("left");
  const goPrev = () => navigate("right");
  const handleFlip = () => {
    if (!exiting) setFlipped((f) => !f);
  };

  const enqueueBackgroundWrite = (label, operation) => {
    pendingWritesRef.current = pendingWritesRef.current
      .then(operation)
      .catch((writeError) => {
        console.error(`${label}:`, writeError);
        setWritesFailed(true);
      });
  };

  const rateCurrentCard = (rating) => {
    if (
      !authorizationHeader ||
      !activeSetId ||
      !current?.id ||
      ratedCardIds.includes(current.id) ||
      pendingAttemptRef.current?.cardId === current.id
    ) {
      return;
    }
    const attempt = {
      cardId: current.id,
      rating,
      attemptId: randomUuid(),
    };
    const request = {
      cardId: current.id,
      rating,
      attemptId: attempt.attemptId,
    };
    pendingAttemptRef.current = attempt;
    setRatedCardIds((previous) => previous.includes(current.id) ? previous : [...previous, current.id]);
    enqueueBackgroundWrite("No se pudo guardar la autoevaluación", async () => {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(activeSetId)}/attempts?subjectId=${encodeURIComponent(String(subject.id))}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: authorizationHeader },
          body: JSON.stringify(request),
        },
      );
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
    });

    if (reviewMode) {
      const recent = [...recentlyShownCardIds, current.id].slice(-3);
      setRecentlyShownCardIds(recent);
      setSelectingNextCard(true);
      selectWeightedReviewCard(activeSetId, recent)
        .then((nextCard) => {
          const nextIndex = cards.findIndex((card) => card.id === nextCard?.id);
          if (nextIndex >= 0) {
            setIndex(nextIndex);
            setFlipped(false);
          }
          pendingAttemptRef.current = null;
        })
        .catch((selectionError) => {
          console.error("No se pudo seleccionar la próxima tarjeta de repaso:", selectionError);
        })
        .finally(() => setSelectingNextCard(false));
    } else if (index === cards.length - 1) {
      setSessionCompleted(true);
    } else {
      goNextAfterRating();
    }
  };

  const goNextAfterRating = () => navigate("left", true);

  const slideClass = exiting
    ? `fc-slide-exit-${slideDir}`
    : slideDir
      ? `fc-slide-enter-${slideDir}`
      : "";

  const finishReviewSession = () => setSessionCompleted(true);

  const leaveStudy = () => {
    onBack();
  };

  const returnToLibrary = () => {
    setCards([]);
    setActiveSetId(null);
    setActiveSet(null);
    setReviewMode(false);
    setRecentlyShownCardIds([]);
    // Si el set se acababa de generar, el generador seguía abierto y la
    // biblioteca (que exige !showGenerator) quedaba tapada por el formulario.
    setShowGenerator(false);
    resetStudyRun();
  };

  const loadingSummary =
    sessionCompleted &&
    Boolean(activeSet) &&
    Boolean(authorizationHeader) &&
    !summary &&
    !summaryError;

  const sourceFiles = (activeSet?.source_file_ids || []).map((fileId) => {
    const file = files.find((candidate) => candidate.id === fileId);
    return {
      id: fileId,
      name: file ? file.filename || file.name : null,
    };
  });

  const openSourceFile = async (file) => {
    if (!file.name || !user?.token) return;
    try {
      setError("");
      const objectUrl = await openFileInNewTab({
        fileId: file.id,
        filename: file.name,
        token: user.token,
      });
      objectUrlsRef.current.push(objectUrl);
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  // Segunda ronda: sólo las tarjetas flojas de este mismo set, la más débil primero.
  // Ese orden es el que implica el peso (1 - mastery)² de la política de repaso,
  // pero determinístico.
  const startReinforcementRound = async () => {
    if (!activeSetId || !activeSet || startingRound) return;
    setStartingRound(true);
    setError("");
    try {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(activeSetId)}/mastery?subjectId=${encodeURIComponent(String(subject.id))}`,
        { headers: { Authorization: authorizationHeader } },
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok)
        throw new Error(body.error || "No se pudo preparar la ronda de repaso");

      const masteryByCard = new Map(
        (Array.isArray(body) ? body : []).map((row) => [
          row.card_id,
          Number(row.mastery_score) || 0,
        ]),
      );
      const weakCards = (activeSet.cards || [])
        .map((card) => ({ card, mastery: masteryByCard.get(card.id) ?? 0 }))
        .filter((entry) => entry.mastery < MASTERY_REVIEW_THRESHOLD)
        .sort((first, second) => first.mastery - second.mastery)
        .map((entry) => entry.card);

      if (weakCards.length === 0) {
        setSummaryError(
          "No quedan tarjetas flojas en este set: ya las dominás todas.",
        );
        return;
      }

      // La última tarjeta de la ronda anterior no pasa por navigate(), que es
      // quien limpia este ref: sin esto, una tarjeta repetida arrancaría
      // bloqueada y no se podría calificar.
      pendingAttemptRef.current = null;
      setCards(weakCards);
      setIndex(0);
      setFlipped(false);
      setRatedCardIds([]);
      setReviewMode(false);
      setRecentlyShownCardIds([]);
      setSummary(null);
      setSummaryError("");
      setSessionCompleted(false);
      setRoundNumber((previous) => previous + 1);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setStartingRound(false);
    }
  };

  if (sessionCompleted) {
    return (
      <div className="fc-page">
        <button className="btn-back" onClick={leaveStudy}>
          ← Volver
        </button>

        <div className="fc-summary">
          <img src={dinaHappyWriting} alt="Dina celebrando que terminaste el set" />
          <h1 className="fc-summary-title">¡Set de flashcards terminado!</h1>
          <p className="fc-sub">
            {activeSet?.title || subject.name}
            {roundNumber > 1 ? ` · Ronda ${roundNumber}` : ""}
          </p>

          {loadingSummary && (
            <p className="fc-generator-status" role="status">
              Calculando tu progreso...
            </p>
          )}

          {!loadingSummary && summary && summary.topics.length > 0 && (
            <>
              <div className="fc-summary-score">
                <span className="fc-summary-label">Dominio promedio de los temas</span>
                <strong>{percent(summary.averageMastery)}%</strong>
                <div className="fc-progress-bar">
                  <div
                    className="fc-progress-fill"
                    style={{ width: `${percent(summary.averageMastery)}%` }}
                  />
                </div>
              </div>

              {summary.weakest && (
                <p className="fc-summary-weakest">
                  El tema que más necesitás repasar es{" "}
                  <strong>{summary.weakest.name}</strong> ({percent(summary.weakest.mastery)}%).
                </p>
              )}

              <div className="fc-summary-topics">
                <h2>Tus temas en este set</h2>
                {summary.topics.map((topic) => (
                  <div className="fc-summary-topic" key={topic.topicId}>
                    <div className="fc-summary-topic-head">
                      <span>{topic.name}</span>
                      <span>{percent(topic.mastery)}%</span>
                    </div>
                    <div className="fc-progress-bar">
                      <div
                        className="fc-progress-fill"
                        style={{ width: `${percent(topic.mastery)}%` }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}

          {!loadingSummary && summary && summary.topics.length === 0 && (
            <p className="fc-generator-status">
              Todavía no hay progreso registrado para los temas de este set.
            </p>
          )}

          {writesFailed && (
            <p className="fc-generator-error">
              Algunas autoevaluaciones no se pudieron guardar, así que estas
              métricas pueden estar incompletas.
            </p>
          )}
          {summaryError && <p className="fc-generator-error">{summaryError}</p>}
          {error && <p className="fc-generator-error">{error}</p>}

          {sourceFiles.length > 0 && (
            <div className="fc-summary-files">
              <h2>Documentos de este set</h2>
              <div className="fc-summary-file-list">
                {sourceFiles.map((file) => (
                  <button
                    type="button"
                    key={file.id}
                    className="fc-summary-file"
                    onClick={() => openSourceFile(file)}
                    disabled={!file.name}
                  >
                    {file.name || "Documento no disponible"}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="fc-summary-actions">
            <button
              type="button"
              className="fc-generate-btn"
              onClick={startReinforcementRound}
              disabled={startingRound}
            >
              {startingRound ? "Preparando..." : "Reforzar lo que fallaste"}
            </button>
            <button
              type="button"
              className="fc-nav-btn"
              onClick={returnToLibrary}
            >
              Volver a mis sets
            </button>
          </div>
        </div>
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
        {selectingNextCard ? (
          // Sin esto quedaría la tarjeta recién calificada en pantalla hasta que
          // resuelva el sorteo, y después saltaría de golpe a otra.
          <div className="fc-scene fc-scene--waiting" role="status">
            <span className="fc-loader" aria-hidden="true" />
            <span>Buscando la próxima tarjeta...</span>
          </div>
        ) : (
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
        )}
      </div>

      {/* ── Navegación ── */}
      <div className="fc-nav">
        {!reviewMode && <button
          className="fc-nav-btn"
          onClick={goPrev}
          disabled={index === 0}
        >← Anterior</button>}
        <button className="fc-nav-btn fc-nav-btn--flip" onClick={handleFlip}>
          {flipped ? "🔄 Ver término" : "🔄 Ver definición"}
        </button>
        {!reviewMode && <button
          className="fc-nav-btn"
          onClick={goNext}
          disabled={cards.length <= 1 || !ratedCardIds.includes(current?.id)}
        >Siguiente →</button>}
        {reviewMode && <button className="fc-nav-btn" onClick={finishReviewSession}>Terminar repaso</button>}
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
                disabled={ratedCardIds.includes(current.id)
                  || pendingAttemptRef.current?.cardId === current.id}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
      {error && <p className="fc-generator-error">{error}</p>}
    </div>
  );
}
