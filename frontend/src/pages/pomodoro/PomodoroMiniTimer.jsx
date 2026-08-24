import {
    POMODORO_MODES,
} from './usePomodoro'

import {
    usePomodoroContext,
} from './PomodoroContext'

import './PomodoroMiniTimer.css'

function formatTime(totalSeconds) {
    const minutes =
        Math.floor(totalSeconds / 60)

    const seconds =
        totalSeconds % 60

    return `${String(minutes).padStart(
        2,
        '0'
    )}:${String(seconds).padStart(
        2,
        '0'
    )}`
}

const MODE_LABELS = {
    [POMODORO_MODES.FOCUS]:
        'Estudio',

    [POMODORO_MODES.SHORT_BREAK]:
        'Descanso',

    [POMODORO_MODES.LONG_BREAK]:
        'Descanso largo',
}

export default function PomodoroMiniTimer({
    onOpen,
}) {
    const {
        mode,
        secondsLeft,
        isRunning,
        hasStarted,
        start,
        pause,
    } = usePomodoroContext()

    if (!hasStarted) {
        return null
    }

    return (
        <div
            className={[
                'pomodoro-mini',
                isRunning
                    ? 'is-running'
                    : 'is-paused',
            ]
                .filter(Boolean)
                .join(' ')}
        >
            <button
                type="button"
                className="pomodoro-mini-main"
                onClick={onOpen}
                aria-label="Abrir Pomodoro"
            >
                <span
                    className="pomodoro-mini-icon"
                    aria-hidden="true"
                >
                    ⏱
                </span>

                <span className="pomodoro-mini-content">
                    <small>
                        {MODE_LABELS[mode]}
                    </small>

                    <strong>
                        {formatTime(
                            secondsLeft
                        )}
                    </strong>
                </span>
            </button>

            <button
                type="button"
                className="pomodoro-mini-control"
                onClick={
                    isRunning
                        ? pause
                        : start
                }
                aria-label={
                    isRunning
                        ? 'Pausar Pomodoro'
                        : 'Continuar Pomodoro'
                }
            >
                {isRunning ? '⏸️' : '▶️'}
            </button>
        </div>
    )
}