import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { randomUuid } from "../lib/uuid";
import { openFileInNewTab } from "../lib/openFile";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import "../styles/library.css";
import "./FlashcardsPage.css";
import dinaEmptyFlashcards from "../assets/dina/dina-empty-flashcards.png";
import dinaHappyWriting from "../assets/dina/dina-happy-writing.png";

const difficultyLabels = {
  basic: "Básico",
  intermediate: "Intermedio",
  advanced: "Avanzado",
};

/** Por debajo de esto, una tarjeta entra en la ronda de refuerzo. */
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
  const [setPendingDeletion, setSetPendingDeletion] = useState(null);
  const [toast, setToast] = useState("");
  const [files, setFiles] = useState([]);
  const [selectedFileIds, setSelectedFileIds] = useState([]);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [cardCount, setCardCount] = useState(10);
  const [cardFormat, setCardFormat] = useState("term_definition");
  const [difficulty, setDifficulty] = useState("basic");
  const [loadingFiles, setLoadingFiles] = useState(true);
  const [pendingSets, setPendingSets] = useState([]);
  const [error, setError] = useState("");
  const [index, setIndex] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [slideDir, setSlideDir] = useState(null); // 'left' | 'right' | null
  const [exiting, setExiting] = useState(false);
  const [activeSetId, setActiveSetId] = useState(null);
  const [activeSet, setActiveSet] = useState(null);
  const [ratingsByPosition, setRatingsByPosition] = useState({});
  const [sessionCompleted, setSessionCompleted] = useState(false);
  const [summary, setSummary] = useState(null);
  const [summaryError, setSummaryError] = useState("");
  const [writesFailed, setWritesFailed] = useState(false);
  const [roundNumber, setRoundNumber] = useState(1);
  const [startingRound, setStartingRound] = useState(false);
  const [openingSetId, setOpeningSetId] = useState(null);
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
    setRatingsByPosition({});
    setSessionCompleted(false);
    setSummary(null);
    setSummaryError("");
    setWritesFailed(false);
    setRoundNumber(1);
  };

  // El aviso de error es pasajero: se va solo, sin que haya que cerrarlo.
  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(""), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

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
        }));

      // De mayor a menor mastery: la lista se lee como un ranking. El tema más
      // flojo ya se destaca aparte, así que no hace falta que abra la lista.
      topics.sort(
        (first, second) =>
          second.mastery - first.mastery || first.name.localeCompare(second.name),
      );

      const averageMastery = topics.length
        ? topics.reduce((total, topic) => total + topic.mastery, 0) / topics.length
        : 0;
      const weakest = topics.length
        ? [...topics].sort(
            (first, second) =>
              first.mastery - second.mastery || first.name.localeCompare(second.name),
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

  // Generar no deja al usuario esperando en el formulario: cierra la vista, agrega
  // el set a la lista como pendiente y resuelve en segundo plano. Se puede lanzar
  // más de uno y cada uno se resuelve por su cuenta.
  const generate = async () => {
    if (!authorizationHeader || selectedFileIds.length === 0) return;

    const pending = {
      id: randomUuid(),
      title: title.trim() || "Set nuevo",
    };
    setPendingSets((previous) => [pending, ...previous]);
    setShowGenerator(false);
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

      // Aparece en la lista listo para abrir; no se abre solo, porque el usuario
      // ya se fue del formulario y puede estar haciendo otra cosa.
      setSavedSets((previousSets) => [body, ...previousSets]);
    } catch (requestError) {
      console.error("Error generando flashcards:", requestError);
      setToast(requestError.message);
    } finally {
      setPendingSets((previous) => previous.filter((item) => item.id !== pending.id));
    }
  };

  const fetchReviewRound = async (flashcardSetId) => {
    const response = await fetch(
      `/api/flashcards/${encodeURIComponent(flashcardSetId)}/review-round`,
      { headers: { Authorization: authorizationHeader } },
    );
    const body = await response.json().catch(() => ({}));
    if (!response.ok)
      throw new Error(body.error || "No se pudo preparar la ronda de repaso");
    return Array.isArray(body.cards) ? body.cards : [];
  };

  const openSet = async (flashcardSet, weightedReview = false) => {
    if (!Array.isArray(flashcardSet.cards) || flashcardSet.cards.length === 0)
      return;
    if (openingSetId) return;
    try {
      setError("");
      setOpeningSetId(flashcardSet.id);

      // La ronda se resuelve antes de montar la vista de estudio: si se montara
      // primero, se vería la primera tarjeta del set y después saltaría a otra.
      const cardsToStudy = weightedReview
        ? await fetchReviewRound(flashcardSet.id)
        : flashcardSet.cards;
      if (cardsToStudy.length === 0) {
        throw new Error("La ronda de repaso quedó vacía");
      }

      setActiveSetId(flashcardSet.id);
      setActiveSet(flashcardSet);
      resetStudyRun();
      setCards(cardsToStudy);
      setIndex(0);
      setFlipped(false);
    } catch (requestError) {
      setToast(requestError.message);
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
        `/api/flashcards/${encodeURIComponent(editingSetId)}`,
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
    setToast("");
    try {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(flashcardSetId)}`,
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
      setSetPendingDeletion(null);
    } catch (requestError) {
      setToast(requestError.message);
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
        <div className="page-loading" role="status" aria-live="polite">
          <span className="loader loader--lg" aria-hidden="true" />
          <p>Cargando tus sets...</p>
        </div>
      </div>
    );
  }

  if (cards.length === 0 && !showGenerator) {
    const hasNoDocuments = !loadingFiles && files.length === 0;
    // Un set en curso ya ocupa la lista: mostrar "todavía no hay sets" al lado
    // de una fila generándose sería contradictorio.
    const hasNoSavedSets =
      !loadingSets && savedSets.length === 0 && pendingSets.length === 0;

    return (
      <div className="fc-page">
        <button className="btn-back" onClick={onBack}>
          ← Volver
        </button>
        <div className="library">
          <div className="library-header">
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
        <div className="library-list">
          {pendingSets.map((pending) => (
            <div
              className="library-item library-item--pending"
              key={pending.id}
              aria-busy="true"
            >
              <div className="fc-set-open">
                <span className="fc-set-title">{pending.title}</span>
                <span className="fc-set-meta">
                  <span className="loader" aria-hidden="true" />
                  Dina está preparando tus flashcards...
                </span>
              </div>
            </div>
          ))}
          {savedSets.map((flashcardSet) => (
            <div className="library-item" key={flashcardSet.id}>
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
                    className="fc-set-review"
                    onClick={() => openSet(flashcardSet, true)}
                    disabled={Boolean(openingSetId)}
                  >
                    {openingSetId === flashcardSet.id
                      ? "Preparando..."
                      : "Repasar"}
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
                    onClick={() => setSetPendingDeletion(flashcardSet)}
                  >
                    Eliminar
                  </button>
                </>
              )}
            </div>
          ))}
          </div>
        </div>
        {setPendingDeletion && (
          <ConfirmDeleteModal
            eyebrow="Eliminar set"
            title="¿Eliminar este set de flashcards?"
            name={setPendingDeletion.title}
            message="Se pierden también sus tarjetas y el progreso asociado. Esta acción no se puede deshacer."
            confirmLabel="Eliminar set"
            busy={deletingSetId === setPendingDeletion.id}
            onCancel={() => setSetPendingDeletion(null)}
            onConfirm={() => deleteSet(setPendingDeletion.id)}
          />
        )}

        {/* Por portal, por lo mismo que el modal: un ancestro con transform o filter
            recorta el position: fixed y el aviso quedaría dentro de la página. */}
        {toast &&
          createPortal(
            <div className="fc-toast" role="alert">
              {toast}
            </div>,
            document.body,
          )}
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
            {error && <p className="fc-generator-error">{error}</p>}
            <button
              type="button"
              className="fc-generate-btn"
              onClick={generate}
              disabled={loadingFiles || selectedFileIds.length === 0}
            >
              Generar flashcards
            </button>
          </div>
        </div>
      </div>
    );
  }

  const current = cards[index];

  const navigate = (dir, afterRating = false) => {
    if (exiting || sessionCompleted) return;
    if (dir === "left" && !afterRating && !ratingsByPosition[index]) return;
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

  const enqueueBackgroundWrite = (label, operation, context = {}) => {
    pendingWritesRef.current = pendingWritesRef.current
      .then(operation)
      .catch((writeError) => {
        // Warning y no error: la app sigue andando, pero el intento se perdió y
        // el mastery queda desactualizado. Va con contexto porque la escritura
        // es fire-and-forget y no hay reintento: sin el cardId no hay forma de
        // saber después cuál calificación falta.
        console.warn(`${label}:`, { ...context, error: writeError.message });
        setWritesFailed(true);
      });
  };

  const postAttempt = (position, cardId, rating) => {
    const request = { cardId, rating, attemptId: randomUuid() };
    setRatingsByPosition((previous) => ({
      ...previous,
      [position]: { attemptId: request.attemptId, cardId, rating },
    }));
    enqueueBackgroundWrite("No se pudo guardar la autoevaluación", async () => {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(activeSetId)}/attempts`,
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
    }, { cardId, attemptId: request.attemptId, rating });
  };

  // Corrige la nota de un intento ya registrado. No manda otro intento a propósito:
  // el mastery se reconstruye reproduciendo el historial, así que calificar de nuevo
  // aplicaría el EMA dos veces y el misclick quedaría pesando para siempre.
  const patchAttempt = (position, previous, rating) => {
    setRatingsByPosition((current) => ({
      ...current,
      [position]: { ...previous, rating },
    }));
    enqueueBackgroundWrite("No se pudo corregir la autoevaluación", async () => {
      const response = await fetch(
        `/api/flashcards/${encodeURIComponent(activeSetId)}/attempts/${encodeURIComponent(previous.attemptId)}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json", Authorization: authorizationHeader },
          body: JSON.stringify({ cardId: previous.cardId, rating }),
        },
      );
      if (response.status === 404) {
        // El POST original nunca llegó. La corrección crea el intento con la nota
        // buena y de paso recupera la escritura perdida.
        postAttempt(position, previous.cardId, rating);
        return;
      }
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
    }, { cardId: previous.cardId, attemptId: previous.attemptId, rating });
  };

  const rateCurrentCard = (rating) => {
    // Indexado por posición, no por card.id: una ronda de repaso puede traer la
    // misma tarjeta más de una vez y cada aparición se califica por separado.
    if (!authorizationHeader || !activeSetId || !current?.id) return;

    const previous = ratingsByPosition[index];
    if (previous) {
      // Ya calificada: esto es una corrección, no un intento nuevo.
      if (previous.rating !== rating) patchAttempt(index, previous, rating);
      // No avanza: si volviste con "Anterior" fue a propósito.
      return;
    }

    if (pendingAttemptRef.current?.position === index) return;
    pendingAttemptRef.current = { position: index, cardId: current.id };
    postAttempt(index, current.id, rating);

    if (index === cards.length - 1) {
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

  const leaveStudy = () => {
    onBack();
  };

  const returnToLibrary = () => {
    setCards([]);
    setActiveSetId(null);
    setActiveSet(null);
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

  // Otra ronda sobre el mismo set. Como el mastery ya se actualizó con los ratings
  // de la ronda anterior, pedirla de nuevo la reordena sola: las que mejoraron
  // dejan de repetirse y las que siguen flojas vuelven a aparecer.
  const startAnotherRound = async () => {
    if (!activeSetId || startingRound) return;
    setStartingRound(true);
    setError("");
    try {
      // Las escrituras son fire-and-forget: sin esperarlas, la ronda se armaría
      // con el mastery de antes de esta pasada.
      await pendingWritesRef.current;
      const round = await fetchReviewRound(activeSetId);
      if (round.length === 0) {
        setSummaryError("La ronda de repaso quedó vacía.");
        return;
      }

      // La última tarjeta de la ronda anterior no pasa por navigate(), que es
      // quien limpia este ref: sin esto, si vuelve a salir primera, arrancaría
      // bloqueada y no se podría calificar.
      pendingAttemptRef.current = null;
      setCards(round);
      setIndex(0);
      setFlipped(false);
      setRatingsByPosition({});
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
          <div className="fc-summary-header">
            <img src={dinaHappyWriting} alt="Dina celebrando que terminaste el set" />
            <h1 className="fc-summary-title">¡Set de flashcards terminado!</h1>
            <p className="fc-sub">
              {activeSet?.title || subject.name}
              {roundNumber > 1 ? ` · Ronda ${roundNumber}` : ""}
            </p>
          </div>

          <div className="fc-summary-columns">
            {/* Izquierda: lo que está disponible al instante. */}
            <div className="fc-summary-side">
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
                  onClick={startAnotherRound}
                  disabled={startingRound}
                >
                  {startingRound ? "Preparando..." : "Otra ronda"}
                </button>
                <button
                  type="button"
                  className="fc-nav-btn"
                  onClick={returnToLibrary}
                >
                  Volver a mis sets
                </button>
              </div>

              {writesFailed && (
                <p className="fc-generator-error">
                  Algunas autoevaluaciones no se pudieron guardar, así que estas
                  métricas pueden estar incompletas.
                </p>
              )}
              {summaryError && <p className="fc-generator-error">{summaryError}</p>}
              {error && <p className="fc-generator-error">{error}</p>}
            </div>

            {/* Derecha: el progreso, que depende de que drene la cola de escrituras. */}
            <div className="fc-summary-progress">
              {loadingSummary && (
                <div className="fc-summary-loading" role="status" aria-live="polite">
                  <span className="loader loader--lg" aria-hidden="true" />
                  <span>Calculando tu progreso...</span>
                </div>
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
                    <div className="fc-summary-topic-list">
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
                  </div>
                </>
              )}

              {!loadingSummary && summary && summary.topics.length === 0 && (
                <p className="fc-generator-status">
                  Todavía no hay progreso registrado para los temas de este set.
                </p>
              )}
            </div>
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
          {index + 1} / {cards.length}
        </span>
        <div className="fc-progress-bar">
          <div
            className="fc-progress-fill"
            style={{ width: `${((index + 1) / cards.length) * 100}%` }}
          />
        </div>
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
        <button
          className="fc-nav-btn"
          onClick={goPrev}
          disabled={index === 0}
        >← Anterior</button>
        <button className="fc-nav-btn fc-nav-btn--flip" onClick={handleFlip}>
          {flipped ? "🔄 Ver término" : "🔄 Ver definición"}
        </button>
        <button
          className="fc-nav-btn"
          onClick={goNext}
          disabled={cards.length <= 1 || !ratingsByPosition[index]}
        >Siguiente →</button>
      </div>
      {flipped && current?.id && activeSetId && (
        <div className="fc-rating" role="group" aria-label="Autoevaluación">
          <span className="fc-rating-title">¿Qué tan bien la sabías?</span>
          <div className="fc-rating-options">
            {ratingLabels.map((label, rating) => (
              <button
                type="button"
                key={label}
                className={`fc-rating-option ${
                  ratingsByPosition[index]?.rating === rating ? "selected" : ""
                }`}
                aria-pressed={ratingsByPosition[index]?.rating === rating}
                onClick={() => rateCurrentCard(rating)}
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
