import usePomodoro from './usePomodoro'
import PomodoroContext from './pomodoroContextValue'

export function PomodoroProvider({
    children,
}) {
    const pomodoro = usePomodoro()

    return (
        <PomodoroContext.Provider
            value={pomodoro}
        >
            {children}
        </PomodoroContext.Provider>
    )
}