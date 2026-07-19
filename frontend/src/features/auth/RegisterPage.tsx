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
        setError('Email này đã được đăng ký');
      } else if (isAxiosError(err) && err.response?.status === 400) {
        setError('Mật khẩu tối thiểu 8 ký tự');
      } else {
        setError('Có lỗi xảy ra, thử lại sau');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <form onSubmit={onSubmit} className="bg-white p-8 rounded-xl shadow w-96 space-y-4">
        <h1 className="text-2xl font-bold text-center">Tạo tài khoản Meetly</h1>
        <input className="w-full border rounded-lg px-3 py-2" placeholder="Họ tên"
               value={fullName} onChange={(e) => setFullName(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="email" placeholder="Email"
               value={email} onChange={(e) => setEmail(e.target.value)} required />
        <input className="w-full border rounded-lg px-3 py-2" type="password"
               placeholder="Mật khẩu (≥ 8 ký tự)" minLength={8}
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button className="w-full bg-blue-600 text-white rounded-lg py-2 disabled:opacity-50"
                disabled={busy} type="submit">
          {busy ? 'Đang tạo...' : 'Đăng ký'}
        </button>
        <p className="text-sm text-center text-gray-600">
          Đã có tài khoản? <Link className="text-blue-600" to="/login">Đăng nhập</Link>
        </p>
      </form>
    </div>
  );
}
