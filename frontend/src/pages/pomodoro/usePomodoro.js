import { useEffect, useRef, useState } from 'react'

export const POMODORO_MODES = {
    FOCUS: 'focus',
    SHORT_BREAK: 'shortBreak',
    LONG_BREAK: 'longBreak',
}

const STORAGE_KEY = 'studina-pomodoro-settings'

const DEFAULT_SETTINGS = {
    focusMinutes: 25,
    shortBreakMinutes: 5,
    longBreakMinutes: 15,
    sessionsPerCycle: 4,
}

function getStoredSettings() {
    try {
        const stored = localStorage.getItem(STORAGE_KEY)

        if (!stored) {
            return DEFAULT_SETTINGS
        }

        return {
            ...DEFAULT_SETTINGS,
            ...JSON.parse(stored),
        }
    } catch {
        return DEFAULT_SETTINGS
    }
}

function getModeDuration(mode, settings) {
    switch (mode) {
        case POMODORO_MODES.SHORT_BREAK:
            return settings.shortBreakMinutes * 60

        case POMODORO_MODES.LONG_BREAK:
            return settings.longBreakMinutes * 60

        case POMODORO_MODES.FOCUS:
        default:
            return settings.focusMinutes * 60
    }
}

function playCompletionSound() {
    try {
        const AudioContext =
            window.AudioContext ||
            window.webkitAudioContext

        if (!AudioContext) {
            return
        }

        const context = new AudioContext()
        const oscillator = context.createOscillator()
        const gain = context.createGain()

        oscillator.type = 'sine'
        oscillator.frequency.setValueAtTime(
            880,
            context.currentTime
        )

        gain.gain.setValueAtTime(
            0.15,
            context.currentTime
        )

        gain.gain.exponentialRampToValueAtTime(
            0.001,
            context.currentTime + 0.8
        )

        oscillator.connect(gain)
        gain.connect(context.destination)

        oscillator.start()
        oscillator.stop(context.currentTime + 0.8)
    } catch {
        // El sonido no es crítico para el funcionamiento.
    }
}

export default function usePomodoro() {
    const initialSettings = getStoredSettings()

    const [settings, setSettings] =
        useState(initialSettings)

    const [mode, setMode] = useState(
        POMODORO_MODES.FOCUS
    )

    const [secondsLeft, setSecondsLeft] =
        useState(
            getModeDuration(
                POMODORO_MODES.FOCUS,
                initialSettings
            )
        )

    const [isRunning, setIsRunning] =
        useState(false)

    const [hasStarted, setHasStarted] =
        useState(false)

    const [
        completedFocusSessions,
        setCompletedFocusSessions,
    ] = useState(0)

    const deadlineRef = useRef(null)

    const changeMode = (
        nextMode,
        currentSettings = settings
    ) => {
        setMode(nextMode)

        setSecondsLeft(
            getModeDuration(
                nextMode,
                currentSettings
            )
        )

        setIsRunning(false)
        deadlineRef.current = null
    }

    const finishCurrentPhase = () => {
        playCompletionSound()

        if (mode === POMODORO_MODES.FOCUS) {
            const nextCompleted =
                completedFocusSessions + 1

            setCompletedFocusSessions(
                nextCompleted
            )

            const shouldTakeLongBreak =
                nextCompleted %
                settings.sessionsPerCycle ===
                0

            changeMode(
                shouldTakeLongBreak
                    ? POMODORO_MODES.LONG_BREAK
                    : POMODORO_MODES.SHORT_BREAK
            )

            return
        }

        changeMode(POMODORO_MODES.FOCUS)
    }

    useEffect(() => {
        if (!isRunning) {
            return undefined
        }

        const intervalId = window.setInterval(
            () => {
                const remainingSeconds = Math.max(
                    0,
                    Math.ceil(
                        (deadlineRef.current -
                            Date.now()) /
                        1000
                    )
                )

                setSecondsLeft(remainingSeconds)

                if (remainingSeconds === 0) {
                    window.clearInterval(intervalId)
                    finishCurrentPhase()
                }
            },
            250
        )

        return () => {
            window.clearInterval(intervalId)
        }
    }, [
        isRunning,
        mode,
        completedFocusSessions,
        settings,
    ])

    useEffect(() => {
        localStorage.setItem(
            STORAGE_KEY,
            JSON.stringify(settings)
        )
    }, [settings])

    const start = () => {
        if (isRunning) {
            return
        }

        setHasStarted(true)

        deadlineRef.current =
            Date.now() + secondsLeft * 1000

        setIsRunning(true)
    }

    const pause = () => {
        if (!isRunning) {
            return
        }

        const remainingSeconds = Math.max(
            0,
            Math.ceil(
                (deadlineRef.current - Date.now()) /
                1000
            )
        )

        setSecondsLeft(remainingSeconds)
        setIsRunning(false)
        deadlineRef.current = null
    }

    const resetCurrentPhase = () => {
        setIsRunning(false)
        deadlineRef.current = null

        setSecondsLeft(
            getModeDuration(mode, settings)
        )
    }

    const resetCycle = () => {
        setCompletedFocusSessions(0)
        setIsRunning(false)
        setHasStarted(false)
        deadlineRef.current = null

        changeMode(POMODORO_MODES.FOCUS)
    }

    const skip = () => {
        setIsRunning(false)
        deadlineRef.current = null
        finishCurrentPhase()
    }

    const updateSetting = (name, value) => {
        const numericValue = Number(value)

        if (
            !Number.isFinite(numericValue) ||
            numericValue < 1
        ) {
            return
        }

        const nextSettings = {
            ...settings,
            [name]: numericValue,
        }

        setSettings(nextSettings)

        if (!isRunning) {
            setSecondsLeft(
                getModeDuration(
                    mode,
                    nextSettings
                )
            )
        }
    }

    return {
        mode,
        secondsLeft,
        isRunning,
        completedFocusSessions,
        settings,
        hasStarted,
        start,
        pause,
        skip,
        resetCurrentPhase,
        resetCycle,
        updateSetting,
    }
}