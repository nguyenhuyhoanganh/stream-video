import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { useAuthStore } from '../stores/authStore';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<div>dashboard</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, accessToken: null, ready: false });
  });

  it('hiện loading khi chưa ready (UI tiếng Anh)', () => {
    renderAt('/');
    expect(screen.getByText('Loading...')).toBeTruthy();
  });

  it('redirect /login khi ready mà không có user', () => {
    useAuthStore.setState({ ready: true });
    renderAt('/');
    expect(screen.getByText('login page')).toBeTruthy();
  });

  it('render nội dung khi có user', () => {
    useAuthStore.setState({
      ready: true,
      user: { id: '1', email: 'a@b.c', fullName: 'A' },
      accessToken: 't',
    });
    renderAt('/');
    expect(screen.getByText('dashboard')).toBeTruthy();
  });
});
