import StudyPlanWizard from "./StudyPlanWizard";

export default function StudyPlanner({
  subject,
  onBack,
}) {
  return (
    <StudyPlanWizard
      subject={subject}
      onExit={onBack}
    />
  );
}