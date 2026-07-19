import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useAuth } from '../auth/useAuth';
import { MembersDialog } from './MembersDialog';
import { useCreateMeeting, useMyMeetings } from './meetingApi';

export function DashboardPage() {
  const user = useAuthStore((s) => s.user);
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { data: meetings, isLoading } = useMyMeetings();
  const createMeeting = useCreateMeeting();

  const [title, setTitle] = useState('');
  const [startAt, setStartAt] = useState('');
  const [roomType, setRoomType] = useState<'MEETING' | 'WEBINAR'>('MEETING');
  const [membersFor, setMembersFor] = useState<string | null>(null);

  async function meetNow() {
    const m = await createMeeting.mutateAsync({ title: 'Họp nhanh' });
    navigate(`/m/${m.code}`);
  }

  async function schedule(e: FormEvent) {
    e.preventDefault();
    await createMeeting.mutateAsync({
      title,
      scheduledStartAt: startAt ? new Date(startAt).toISOString() : undefined,
      roomType,
    });
    setTitle('');
    setStartAt('');
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow-sm px-6 py-3 flex items-center justify-between">
        <h1 className="text-xl font-bold text-blue-600">Meetly</h1>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-gray-700">{user?.fullName}</span>
          <button onClick={() => void logout()} className="text-gray-500 hover:text-gray-800">
            Đăng xuất
          </button>
        </div>
      </header>

      <main className="max-w-3xl mx-auto p-6 space-y-6">
        <div className="flex gap-3">
          <button onClick={() => void meetNow()} disabled={createMeeting.isPending}
                  className="bg-blue-600 text-white rounded-lg px-5 py-2.5 font-medium disabled:opacity-50">
            Họp ngay
          </button>
        </div>

        <form onSubmit={schedule} className="bg-white rounded-xl shadow p-4 flex gap-3 items-end">
          <label className="flex-1 text-sm">
            Tiêu đề
            <input className="mt-1 w-full border rounded-lg px-3 py-2" value={title}
                   onChange={(e) => setTitle(e.target.value)} required placeholder="Họp team tuần" />
          </label>
          <label className="text-sm">
            Bắt đầu lúc
            <input className="mt-1 border rounded-lg px-3 py-2" type="datetime-local"
                   value={startAt} onChange={(e) => setStartAt(e.target.value)} />
          </label>
          <label className="text-sm">
            Loại phòng
            <select className="mt-1 border rounded-lg px-3 py-2" value={roomType}
                    onChange={(e) => setRoomType(e.target.value as 'MEETING' | 'WEBINAR')}>
              <option value="MEETING">Họp kín</option>
              <option value="WEBINAR">Webinar</option>
            </select>
          </label>
          <button className="bg-gray-800 text-white rounded-lg px-4 py-2 disabled:opacity-50"
                  disabled={createMeeting.isPending} type="submit">
            Đặt lịch
          </button>
        </form>

        <section className="bg-white rounded-xl shadow divide-y">
          <h2 className="px-4 py-3 font-semibold">Meeting của tôi</h2>
          {isLoading && <p className="px-4 py-3 text-gray-500">Đang tải...</p>}
          {meetings?.length === 0 && (
            <p className="px-4 py-3 text-gray-500">Chưa có meeting nào</p>
          )}
          {meetings?.map((m) => (
            <div key={m.id} className="px-4 py-3 flex items-center justify-between">
              <div>
                <p className="font-medium">{m.title}</p>
                <p className="text-sm text-gray-500">
                  {new Date(m.scheduledStartAt).toLocaleString('vi-VN')} · {m.code} · {m.status}
                </p>
              </div>
              <span className="flex items-center gap-3">
                <button onClick={() => setMembersFor(m.id)}
                        className="text-gray-600 text-sm hover:underline">
                  Thành viên
                </button>
                {(m.status === 'SCHEDULED' || m.status === 'LIVE') && (
                  <button onClick={() => navigate(`/m/${m.code}`)}
                          className="text-blue-600 font-medium hover:underline">
                    Vào phòng
                  </button>
                )}
              </span>
            </div>
          ))}
        </section>
      </main>
      {membersFor && <MembersDialog meetingId={membersFor} onClose={() => setMembersFor(null)} />}
    </div>
  );
}
