import "./ProfilePage.css";

const getInitials = (name = "") => {
  const words = name
    .trim()
    .split(/\s+/)
    .filter(Boolean);

  if (words.length === 0) {
    return "U";
  }

  return words
    .slice(0, 2)
    .map((word) => word[0].toUpperCase())
    .join("");
};

export default function ProfilePage({
  user,
  onBack,
}) {
  const initials = getInitials(user?.name);

  return (
    <section className="profile-page">
      <button
        type="button"
        className="btn-back"
        onClick={onBack}
      >
        ← Volver
      </button>

      <header className="profile-header">
        <div>
          <h1 className="profile-title">
            Mi perfil
          </h1>

          <p className="profile-subtitle">
            Consultá la información asociada a tu cuenta.
          </p>
        </div>

        <div
          className="profile-header-icon"
          aria-hidden="true"
        >
          🪪
        </div>
      </header>

      <div className="profile-content">
        <aside className="profile-summary-card">
          <div className="profile-avatar">
            {initials}
          </div>

          <h2 className="profile-name">
            {user?.name || "Usuario"}
          </h2>

          <p className="profile-email">
            {user?.email || "Email no disponible"}
          </p>

          <span className="profile-status">
            Cuenta activa
          </span>
        </aside>

        <section className="profile-details-card">
          <div className="profile-card-heading">
            <div>
              <h2>Información personal</h2>

              <p>
                Estos son los datos registrados en Studina.
              </p>
            </div>
          </div>

          <dl className="profile-details">
            <div className="profile-detail">
              <dt>Nombre completo</dt>
              <dd>{user?.name || "No disponible"}</dd>
            </div>

            <div className="profile-detail">
              <dt>Email</dt>
              <dd>{user?.email || "No disponible"}</dd>
            </div>
          </dl>

          <div className="profile-info-box">
            <span aria-hidden="true">💡</span>

            <p>
              Más adelante vas a poder editar tu nombre
              y personalizar tu perfil desde esta sección.
            </p>
          </div>
        </section>
      </div>
    </section>
  );
}