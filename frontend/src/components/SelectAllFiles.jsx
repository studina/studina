import { useEffect, useRef } from 'react'

/**
 * El "seleccionar todos" de los selectores de documentos.
 *
 * Es un único checkbox en lugar de dos botones porque el mismo control sirve para lo
 * contrario: con todo elegido, destilda todo. Así el selector no crece con opciones que
 * hacen lo mismo al revés.
 *
 * Lo comparten la generación de flashcards, la de cuestionarios y el chat con Dina: las tres
 * pantallas tienen el mismo par `files` / `selectedFileIds`, y sólo cambian las clases con
 * las que cada una lo pinta.
 */
export default function SelectAllFiles({ className, files, selectedFileIds, onChange }) {
	const checkboxRef = useRef(null)
	const total = files.length
	const selectedCount = files.filter((file) => selectedFileIds.includes(file.id)).length
	const allSelected = total > 0 && selectedCount === total

	// El estado intermedio —algunos sí, otros no— no se puede declarar en JSX: es una
	// propiedad del nodo y no un atributo. Sin esto, elegir un documento suelto dejaría
	// el control vacío, como si no hubiera nada seleccionado.
	useEffect(() => {
		if (checkboxRef.current) {
			checkboxRef.current.indeterminate = selectedCount > 0 && !allSelected
		}
	}, [selectedCount, allSelected])

	// Sin documentos no hay nada que seleccionar, y el selector ya explica que están vacíos.
	if (total === 0) return null

	return (
		<label className={className}>
			<input
				ref={checkboxRef}
				type="checkbox"
				checked={allSelected}
				onChange={() => onChange(allSelected ? [] : files.map((file) => file.id))}
			/>
			<span>
				{allSelected ? 'Deseleccionar todos' : 'Seleccionar todos'}
				{total > 1 && ` (${total})`}
			</span>
		</label>
	)
}
