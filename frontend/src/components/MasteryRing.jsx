import './MasteryRing.css'
import { percent } from '../lib/mastery'

/**
 * El anillo de dominio.
 *
 * Se dibuja a mano con dos círculos y stroke-dasharray: es el único gráfico de la app y no
 * justifica una librería de charts. Un valor null no es 0% — es que todavía no hay nada
 * medido —, así que en ese caso el anillo queda vacío y el centro muestra un guion.
 */
export default function MasteryRing({ value, size = 96, stroke = 9, caption }) {
	const radius = (size - stroke) / 2
	const circumference = 2 * Math.PI * radius
	const ratio = value == null ? 0 : Math.max(0, Math.min(1, Number(value) || 0))
	const label = value == null ? '—' : `${percent(value)}%`

	return (
		<div className="mastery-ring" style={{ width: size, height: size }}>
			<svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
				<circle
					className="mastery-ring-track"
					cx={size / 2}
					cy={size / 2}
					r={radius}
					strokeWidth={stroke}
				/>
				{/* Con ratio 0 el stroke-linecap redondo igual pinta un punto, que se lee
				    como "algo de dominio" donde no hay ninguno. Sin arco no hay punto. */}
				{ratio > 0 && (
					<circle
						className="mastery-ring-value"
						cx={size / 2}
						cy={size / 2}
						r={radius}
						strokeWidth={stroke}
						strokeDasharray={`${circumference * ratio} ${circumference}`}
						transform={`rotate(-90 ${size / 2} ${size / 2})`}
					/>
				)}
			</svg>
			<div className="mastery-ring-center">
				<strong>{label}</strong>
				{caption && <span>{caption}</span>}
			</div>
		</div>
	)
}
