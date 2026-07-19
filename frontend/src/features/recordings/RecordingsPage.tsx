import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchPlaybackUrl, useRecordings } from './recordingApi';

export function RecordingsPage() {
  const { meetingId } = useParams<{ meetingId: string }>();
  const { data: recordings, isLoading } = useRecordings(meetingId!);
  const [playing, setPlaying] = useState<string | null>(null);

  async function play(recordingId: string) {
    setPlaying(await fetchPlaybackUrl(recordingId));
  }

  return (
    <div className="min-h-screen bg-gray-50 p-6 max-w-3xl mx-auto space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Bản ghi cuộc họp</h1>
        <Link to="/" className="text-blue-600 text-sm">← Về trang chính</Link>
      </div>
      {playing && (
        <video controls autoPlay src={playing} className="w-full rounded-xl shadow bg-black" />
      )}
      <section className="bg-white rounded-xl shadow divide-y">
        {isLoading && <p className="px-4 py-3 text-gray-500">Đang tải...</p>}
        {recordings?.length === 0 && (
          <p className="px-4 py-3 text-gray-500">Chưa có bản ghi nào</p>
        )}
        {recordings?.map((r) => (
          <div key={r.id} className="px-4 py-3 flex items-center justify-between text-sm">
            <div>
              <p>{new Date(r.startedAt).toLocaleString('vi-VN')}</p>
              <p className="text-gray-500">
                {r.status}{r.durationSeconds ? ` · ${Math.round(r.durationSeconds / 60)} phút` : ''}
              </p>
            </div>
            {r.status === 'COMPLETED' && (
              <button onClick={() => void play(r.id)}
                      className="text-blue-600 font-medium hover:underline">
                ▶ Xem
              </button>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}
