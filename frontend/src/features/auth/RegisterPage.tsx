import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useAuth } from './useAuth';

export function RegisterPage() {
  const { register } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await register(email, password, fullName);
    } catch (err) {
      if (isAxiosError(err) && err.response?.data?.code === 'EMAIL_TAKEN') {
        setError('That email is already registered');
      } else if (isAxiosError(err) && err.response?.status === 400) {
        setError('Password must be at least 8 characters');
      } else {
        setError('Something went wrong, please try again');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">Create your Meetly account</h1>
        <input className="w-full border rounded-lg px-3 py-2" placeholder="Full name"
               value={fullName} onChange={(e) => setFullName(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="password"
               placeholder="Password (min. 8 characters)" minLength={8}
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button className="w-full bg-blue-600 text-white rounded-lg py-2 disabled:opacity-50"
                disabled={busy} type="submit">
          {busy ? 'Creating...' : 'Sign up'}
        </button>
        <p className="text-sm text-center text-gray-600">
          Already have an account? <Link className="text-blue-600" to="/login">Sign in</Link>
        </p>
      </form>
    </div>
  );
}
