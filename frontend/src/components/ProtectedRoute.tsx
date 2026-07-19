import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

export function ProtectedRoute() {
  const { user, ready } = useAuthStore();
  if (!ready) return <div className="p-8 text-center text-gray-500">Loading...</div>;
  if (!user) return <Navigate to="/login" replace />;
  return <Outlet />;
}
