import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useAuth } from './useAuth';

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (err) {
      setError(isAxiosError(err) && err.response?.status === 401
        ? 'Incorrect email or password'
        : 'Something went wrong, please try again');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">Meetly</h1>
        <input className="w-full border rounded-lg px-3 py-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="password" placeholder="Password"
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button className="w-full bg-blue-600 text-white rounded-lg py-2 disabled:opacity-50"
                disabled={busy} type="submit">
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
        <p className="text-sm text-center text-gray-600">
          No account yet? <Link className="text-blue-600" to="/register">Sign up</Link>
        </p>
      </form>
    </div>
  );
}
