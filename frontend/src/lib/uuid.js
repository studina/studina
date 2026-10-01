/**
 * UUID v4, con fallback para navegadores sin `crypto.randomUUID`.
 *
 * Genera el `attemptId` de cada intento: identifica la acción del usuario (una calificación,
 * una respuesta) con independencia de cuántas veces se envíe el POST. El backend descarta un
 * reenvío con un `attemptId` ya registrado, así que el historial no acumula duplicados y el
 * mastery, que se reconstruye reproduciéndolo, no aplica dos veces la misma calificación.
 *
 * El fallback importa: `crypto.randomUUID` sólo existe en contexto seguro (HTTPS o localhost).
 */
export function randomUuid() {
  return globalThis.crypto?.randomUUID?.()
    || "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
      const random = Math.random() * 16 | 0;
      return (character === "x" ? random : (random & 0x3 | 0x8)).toString(16);
    });
}
