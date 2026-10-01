import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router";
import { setToken } from "../auth/token";

interface LoginState {
  from?: string;
  reason?: "unauthorized";
}

export function LoginPage() {
  const navigate = useNavigate();
  const state = (useLocation().state ?? {}) as LoginState;
  const [token, setTokenInput] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = token.trim();
    if (!trimmed) return;
    setToken(trimmed);
    void navigate(state.from ?? "/", { replace: true });
  };

  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <h1>when-i-off</h1>
        <p className="muted">
          backend의 API 토큰(<code>WIO_API_TOKEN</code>)을 입력하세요.
        </p>
        {state.reason === "unauthorized" && (
          <p role="alert" className="error">
            토큰이 없거나 맞지 않습니다. 다시 입력하세요.
          </p>
        )}
        <label htmlFor="token">API 토큰</label>
        <input
          id="token"
          type="password"
          autoComplete="current-password"
          value={token}
          onChange={(e) => setTokenInput(e.target.value)}
          autoFocus
        />
        <button type="submit" disabled={!token.trim()}>
          로그인
        </button>
      </form>
    </div>
  );
}
