import { useEffect, useState } from 'react'
import dinaStudyPlanner from '../../assets/dina/dina-study-planner.png'
import {
    POMODORO_MODES,
} from './usePomodoro'

import {
    usePomodoroContext,
} from './PomodoroContext'
import './PomodoroPage.css'

const MODE_CONTENT = {
    [POMODORO_MODES.FOCUS]: {
        label: 'Sesión de estudio',
        title: 'Momento de concentrarse',
    },

    [POMODORO_MODES.SHORT_BREAK]: {
        label: 'Descanso corto',
        title: 'Tomate una pausa',
        description:
            'Descansá unos minutos antes del próximo bloque.',
    },

    [POMODORO_MODES.LONG_BREAK]: {
        label: 'Descanso largo',
        title: 'Completaste un ciclo',
        description:
            'Aprovechá este descanso más largo para recuperar energía.',
    },
}

function formatTime(totalSeconds) {
    const minutes = Math.floor(totalSeconds / 60)
    const seconds = totalSeconds % 60

    return `${String(minutes).padStart(
        2,
        '0'
    )}:${String(seconds).padStart(2, '0')}`
}

export default function PomodoroPage({
    subject,
    onBack,
}) {

    const [showSettings, setShowSettings] =
        useState(true)

    const {
        mode,
        secondsLeft,
        isRunning,
        completedFocusSessions,
        settings,
        start,
        pause,
        skip,
        resetCurrentPhase,
        resetCycle,
        updateSetting,
    } = usePomodoroContext()

    const modeContent = MODE_CONTENT[mode]

    const currentModeTotalSeconds =
        mode === POMODORO_MODES.FOCUS
            ? settings.focusMinutes * 60
            : mode === POMODORO_MODES.SHORT_BREAK
                ? settings.shortBreakMinutes * 60
                : settings.longBreakMinutes * 60

    const elapsedSeconds =
        currentModeTotalSeconds - secondsLeft

    const progressPercentage =
        currentModeTotalSeconds > 0
            ? Math.min(
                100,
                Math.max(
                    0,
                    (elapsedSeconds /
                        currentModeTotalSeconds) *
                    100
                )
            )
            : 0

    const nextModeLabel = (() => {
        if (mode !== POMODORO_MODES.FOCUS) {
            return `sesión de estudio de ${settings.focusMinutes} minutos`
        }

        const nextCompleted =
            completedFocusSessions + 1

        const usesLongBreak =
            nextCompleted %
            settings.sessionsPerCycle ===
            0

        if (usesLongBreak) {
            return `descanso largo de ${settings.longBreakMinutes} minutos`
        }

        return `descanso corto de ${settings.shortBreakMinutes} minutos`
    })()

    const currentCycleSession =
        (completedFocusSessions %
            settings.sessionsPerCycle) +
        1

    const completedInCurrentCycle =
        completedFocusSessions %
        settings.sessionsPerCycle

    useEffect(() => {
        const previousTitle = document.title

        document.title = `${formatTime(
            secondsLeft
        )} · ${modeContent.label} · Studina`

        return () => {
            document.title = previousTitle
        }
    }, [
        secondsLeft,
        modeContent.label,
    ])

    return (
        <section className="pomodoro-page">
            <header className="pomodoro-hero">
                <div className="pomodoro-hero-content">
                    <button
                        type="button"
                        className="pomodoro-back-button"
                        onClick={onBack}
                    >
                        ← Volver
                    </button>

                    <p className="pomodoro-eyebrow">
                        Herramienta de concentración
                    </p>

                    <h1>Pomodoro</h1>

                    <p className="pomodoro-hero-description">
                        Organizá tu tiempo de estudio en bloques de concentración y descanso
                        {subject?.name && (
                            <>
                                {' para '}
                                <strong className="pomodoro-subject-name">
                                    {subject.name}
                                </strong>
                                .
                            </>
                        )}
                    </p>
                </div>

                <img
                    className="pomodoro-hero-image"
                    src={dinaStudyPlanner}
                    alt=""
                    aria-hidden="true"
                />
            </header>

            <div className="pomodoro-toolbar">
                <button
                    type="button"
                    className={[
                        'pomodoro-settings-toggle',
                        showSettings ? 'is-active' : '',
                    ]
                        .filter(Boolean)
                        .join(' ')}
                    onClick={() =>
                        setShowSettings(
                            (currentValue) =>
                                !currentValue
                        )
                    }
                    aria-expanded={showSettings}
                    aria-controls="pomodoro-settings"
                >
                    <span aria-hidden="true">⚙</span>

                    {showSettings
                        ? 'Ocultar configuración'
                        : 'Configuración'}
                </button>
            </div>

            <div
                className={[
                    'pomodoro-layout',
                    showSettings
                        ? 'has-settings'
                        : 'settings-hidden',
                ]
                    .filter(Boolean)
                    .join(' ')}
            >
                <article
                    className={`pomodoro-timer-card pomodoro-mode-${mode}`}
                >
                    <div className="pomodoro-mode-status">
                        <p className="pomodoro-mode-label">
                            {modeContent.label}
                        </p>

                        {isRunning && (
                            <span className="pomodoro-running-status">
                                <span
                                    className="pomodoro-running-dot"
                                    aria-hidden="true"
                                />
                            </span>
                        )}
                    </div>

                    <h2>{modeContent.title}</h2>

                    <p className="pomodoro-mode-description">
                        {modeContent.description}
                    </p>

                    <div
                        className="pomodoro-clock"
                        aria-live="polite"
                    >
                        {formatTime(secondsLeft)}
                    </div>

                    <div className="pomodoro-controls">
                        {!isRunning ? (
                            <button
                                type="button"
                                className="pomodoro-primary-button"
                                onClick={start}
                            >
                                {secondsLeft ===
                                    (mode === POMODORO_MODES.FOCUS
                                        ? settings.focusMinutes
                                        : mode ===
                                            POMODORO_MODES.SHORT_BREAK
                                            ? settings.shortBreakMinutes
                                            : settings.longBreakMinutes) *
                                    60
                                    ? 'Iniciar'
                                    : 'Continuar'}
                            </button>
                        ) : (
                            <button
                                type="button"
                                className="pomodoro-primary-button"
                                onClick={pause}
                            >
                                Pausar
                            </button>
                        )}

                        <button
                            type="button"
                            className="pomodoro-secondary-button"
                            onClick={resetCurrentPhase}
                        >
                            Reiniciar
                        </button>

                        <button
                            type="button"
                            className="pomodoro-text-button"
                            onClick={skip}
                        >
                            Saltar bloque
                        </button>
                    </div>

                    <div className="pomodoro-cycle">
                        <div className="pomodoro-cycle-header">
                            <span>
                                Progreso del ciclo
                            </span>

                            <strong>
                                {mode ===
                                    POMODORO_MODES.FOCUS
                                    ? `Pomodoro ${currentCycleSession} de ${settings.sessionsPerCycle}`
                                    : `${completedInCurrentCycle} de ${settings.sessionsPerCycle} completados`}
                            </strong>
                        </div>

                        <div
                            className="pomodoro-cycle-dots"
                            aria-label={`${completedInCurrentCycle} sesiones completadas`}
                        >
                            {Array.from({
                                length:
                                    settings.sessionsPerCycle,
                            }).map((_, index) => (
                                <span
                                    key={index}
                                    className={
                                        index <
                                            completedInCurrentCycle
                                            ? 'completed'
                                            : ''
                                    }
                                />
                            ))}
                        </div>
                    </div>
                </article>

                {showSettings && (
                    <aside
                        id="pomodoro-settings"
                        className="pomodoro-settings-card"
                    >
                        <div className="pomodoro-settings-heading">
                            <div>
                                <p className="pomodoro-eyebrow">
                                    Configuración
                                </p>

                                <h2>
                                    Duración de los bloques
                                </h2>
                            </div>

                            <button
                                type="button"
                                className="pomodoro-reset-cycle"
                                onClick={resetCycle}
                            >
                                Reiniciar ciclo
                            </button>
                        </div>

                        <div className="pomodoro-settings-list">
                            <label>
                                <span>
                                    <strong>Estudio</strong>
                                    <small>Tiempo de concentración</small>
                                </span>

                                <span className="pomodoro-number-field">
                                    <input
                                        type="number"
                                        min="1"
                                        max="120"
                                        value={settings.focusMinutes}
                                        disabled={isRunning}
                                        onChange={(event) =>
                                            updateSetting(
                                                'focusMinutes',
                                                event.target.value
                                            )
                                        }
                                    />
                                    <small>min</small>
                                </span>
                            </label>

                            <label>
                                <span>
                                    <strong>Descanso corto</strong>
                                    <small>
                                        Entre bloques de estudio
                                    </small>
                                </span>

                                <span className="pomodoro-number-field">
                                    <input
                                        type="number"
                                        min="1"
                                        max="60"
                                        value={settings.shortBreakMinutes}
                                        disabled={isRunning}
                                        onChange={(event) =>
                                            updateSetting(
                                                'shortBreakMinutes',
                                                event.target.value
                                            )
                                        }
                                    />
                                    <small>min</small>
                                </span>
                            </label>

                            <label>
                                <span>
                                    <strong>Descanso largo</strong>
                                    <small>
                                        Al terminar un ciclo
                                    </small>
                                </span>

                                <span className="pomodoro-number-field">
                                    <input
                                        type="number"
                                        min="1"
                                        max="120"
                                        value={settings.longBreakMinutes}
                                        disabled={isRunning}
                                        onChange={(event) =>
                                            updateSetting(
                                                'longBreakMinutes',
                                                event.target.value
                                            )
                                        }
                                    />
                                    <small>min</small>
                                </span>
                            </label>

                            <label>
                                <span>
                                    <strong>Pomodoros por ciclo</strong>
                                    <small>
                                        Antes del descanso largo
                                    </small>
                                </span>

                                <span className="pomodoro-number-field">
                                    <input
                                        type="number"
                                        min="1"
                                        max="10"
                                        value={settings.sessionsPerCycle}
                                        disabled={isRunning}
                                        onChange={(event) =>
                                            updateSetting(
                                                'sessionsPerCycle',
                                                event.target.value
                                            )
                                        }
                                    />
                                    <small>ses.</small>
                                </span>
                            </label>
                        </div>

                        <p className="pomodoro-settings-note">
                            La configuración se guarda
                            automáticamente en este navegador.
                        </p>
                    </aside>
                )}
            </div>
        </section>
    )
}