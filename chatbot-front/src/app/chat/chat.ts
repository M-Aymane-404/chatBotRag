import { ChangeDetectorRef, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { MarkdownComponent } from 'ngx-markdown';
import { firstValueFrom } from 'rxjs';

type RagSource = { citation: number; sourceName: string; page?: number; score: number; snippet: string };
type RagAnswer = {
  sessionId: string;
  question: string;
  answer: string;
  transcription?: string | null;
  sources?: RagSource[];
};

type ChatMessage = {
  id: string;
  role: 'user' | 'ai';
  text: string;
  markdown?: boolean;
  sources?: RagSource[];
};

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, MarkdownComponent],
  templateUrl: './chat.html',
  styleUrl: './chat.css',
})
export class Chat implements OnDestroy {
  apiBase = '';
  private readonly maxFileSizeBytes = 20 * 1024 * 1024;

  sessionId = localStorage.getItem('ragSessionId') ?? '';
  question = '';

  messages: ChatMessage[] = [];

  files: File[] = [];
  audioBlob?: Blob;
  recordingSeconds = 0;
  recordingErrorMessage = '';
  fileErrorMessage = '';
  composerHintMessage = '';

  progress = false;
  isRecording = false;
  isDragging = false;
  isSpeaking = false;

  autoplayBlockedMessage = '';

  private _wantAudio = (localStorage.getItem('wantAudio') ?? 'false') === 'true';
  get wantAudio() {
    return this._wantAudio;
  }
  set wantAudio(v: boolean) {
    this._wantAudio = v;
    localStorage.setItem('wantAudio', String(v));
    this.autoplayBlockedMessage = '';
    if (!v) this.stopAnswerAudio();
  }

  private mediaRecorder?: MediaRecorder;
  private audioChunks: Blob[] = [];
  private recordingInterval?: number;
  private recordingStartedAtMs = 0;
  private recordingStoppedPromise?: Promise<void>;
  private recordingStoppedResolve?: () => void;
  recordedAudioUrl?: string;

  private answerAudio?: HTMLAudioElement;
  private answerAudioObjectUrl?: string;

  @ViewChild('fileInput') fileInputRef?: ElementRef<HTMLInputElement>;
  @ViewChild('thread') threadRef?: ElementRef<HTMLElement>;

  constructor(
    private http: HttpClient,
    private cd: ChangeDetectorRef,
  ) {}

  ngOnDestroy() {
    this.stopAnswerAudio();
    this.clearRecordedAudio();
    this.stopRecordingTimer();
  }

  newSession() {
    this.sessionId = crypto.randomUUID();
    localStorage.setItem('ragSessionId', this.sessionId);
    this.messages = [];
    this.files = [];
    this.question = '';
    this.autoplayBlockedMessage = '';
    this.fileErrorMessage = '';
    this.stopAnswerAudio();
    this.clearRecordedAudio();
  }

  openFilePicker() {
    this.fileInputRef?.nativeElement?.click();
  }

  onFilesSelected(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  removeFile(idx: number) {
    this.files.splice(idx, 1);
  }

  onDragOver(ev: DragEvent) {
    ev.preventDefault();
    this.isDragging = true;
  }

  onDragLeave(ev: DragEvent) {
    ev.preventDefault();
    this.isDragging = false;
  }

  onDrop(ev: DragEvent) {
    ev.preventDefault();
    this.isDragging = false;
    const dt = ev.dataTransfer;
    if (!dt?.files?.length) return;
    this.addFiles(Array.from(dt.files));
  }

  private addFiles(incoming: File[]) {
    if (!incoming.length) return;
    this.fileErrorMessage = '';
    const existing = new Set(this.files.map((f) => `${f.name}:${f.size}:${f.lastModified}`));
    const rejected: string[] = [];
    for (const f of incoming) {
      if (f.size > this.maxFileSizeBytes) {
        rejected.push(`${f.name} (${this.formatBytes(f.size)})`);
        continue;
      }
      const key = `${f.name}:${f.size}:${f.lastModified}`;
      if (!existing.has(key)) {
        this.files.push(f);
        existing.add(key);
      }
    }
    if (rejected.length) {
      this.fileErrorMessage =
        `Fichier trop volumineux. Taille max par fichier: ${this.formatBytes(this.maxFileSizeBytes)}. ` +
        `Refuse: ${rejected.join(', ')}.`;
    }
  }

  private formatBytes(bytes: number) {
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  }

  async toggleRecording() {
    if (this.isRecording) {
      this.stopRecording();
    } else {
      await this.startRecording();
    }
  }

  private async startRecording() {
    this.recordingErrorMessage = '';
    this.composerHintMessage = '';

    if (!window.isSecureContext) {
      this.recordingErrorMessage = 'Le micro nécessite HTTPS ou localhost.';
      this.cd.detectChanges();
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      this.recordingErrorMessage = 'Micro non supporté par ce navigateur.';
      this.cd.detectChanges();
      return;
    }
    if (!('MediaRecorder' in window)) {
      this.recordingErrorMessage = 'Enregistrement audio non supporté (MediaRecorder).';
      this.cd.detectChanges();
      return;
    }

    this.clearRecordedAudio();
    this.audioChunks = [];

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = this.pickRecorderMimeType();
      this.mediaRecorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      this.mediaRecorder.ondataavailable = (e: BlobEvent) => {
        if (e.data && e.data.size > 0) this.audioChunks.push(e.data);
      };
      this.mediaRecorder.onerror = (ev: any) => {
        const name = ev?.error?.name || '';
        const msg = ev?.error?.message || '';
        this.recordingErrorMessage = `Erreur micro${name ? ` (${name})` : ''}${msg ? `: ${msg}` : ''}.`;
        this.cd.detectChanges();
      };

      this.recordingStoppedPromise = new Promise<void>((resolve) => {
        this.recordingStoppedResolve = resolve;
      });

      this.mediaRecorder.onstop = () => {
        try {
          const blob = new Blob(this.audioChunks, { type: this.mediaRecorder?.mimeType || 'audio/webm' });
          this.audioBlob = blob && blob.size > 0 ? blob : undefined;
          if (!this.audioBlob) {
            this.recordingErrorMessage = 'Audio vide (réessaie).';
            this.recordingSeconds = 0;
            this.setRecordedAudioUrl(undefined);
          } else {
            this.setRecordedAudioUrl(this.audioBlob);
          }
        } finally {
          this.stopRecordingTimer();
          stream.getTracks().forEach((t) => t.stop());
          this.isRecording = false;
          this.recordingStoppedResolve?.();
          this.recordingStoppedResolve = undefined;
          this.cd.detectChanges();
        }
      };

      this.isRecording = true;
      this.startRecordingTimer();
      this.mediaRecorder.start(250);
    } catch (err: any) {
      const name = err?.name ? String(err.name) : '';
      const msg = err?.message ? String(err.message) : String(err);
      this.recordingErrorMessage = `Impossible d’accéder au micro${name ? ` (${name})` : ''}: ${msg}`;
      this.isRecording = false;
      this.stopRecordingTimer();
    } finally {
      this.cd.detectChanges();
    }
  }

  private stopRecording() {
    this.stopRecordingTimer();
    this.mediaRecorder?.stop();
  }

  clearRecordedAudio() {
    this.audioBlob = undefined;
    this.audioChunks = [];
    this.recordingSeconds = 0;
    this.recordingErrorMessage = '';
    this.composerHintMessage = '';
    this.setRecordedAudioUrl(undefined);
  }

  private pickRecorderMimeType(): string | undefined {
    if (!('MediaRecorder' in window)) return undefined;
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg'];
    for (const t of candidates) {
      try {
        if ((MediaRecorder as any).isTypeSupported?.(t)) return t;
      } catch {}
    }
    return undefined;
  }

  private setRecordedAudioUrl(blob: Blob | undefined) {
    if (this.recordedAudioUrl) {
      try {
        URL.revokeObjectURL(this.recordedAudioUrl);
      } catch {}
      this.recordedAudioUrl = undefined;
    }
    if (blob) {
      this.recordedAudioUrl = URL.createObjectURL(blob);
    }
  }

  private async stopRecordingAndWait() {
    if (!this.isRecording) return;
    const p = this.recordingStoppedPromise;
    try {
      this.stopRecording();
    } catch {}
    if (p) await p;
  }

  get recordingTimeLabel() {
    const totalSeconds = Math.max(0, Math.floor(this.recordingSeconds || 0));
    const mm = Math.floor(totalSeconds / 60);
    const ss = totalSeconds % 60;
    return String(mm).padStart(2, '0') + ':' + String(ss).padStart(2, '0');
  }

  private startRecordingTimer() {
    this.stopRecordingTimer();
    this.recordingStartedAtMs = Date.now();
    this.recordingSeconds = 0;
    this.recordingInterval = window.setInterval(() => {
      this.recordingSeconds = Math.max(0, Math.floor((Date.now() - this.recordingStartedAtMs) / 1000));
      this.cd.detectChanges();
    }, 200);
  }

  private stopRecordingTimer() {
    if (this.recordingInterval) {
      window.clearInterval(this.recordingInterval);
      this.recordingInterval = undefined;
    }
  }

  onKeyDown(ev: KeyboardEvent) {
    if (ev.key === 'Enter' && !ev.shiftKey) {
      ev.preventDefault();
      void this.send();
    }
  }

  async send() {
    if (this.isRecording) {
      await this.stopRecordingAndWait();
    }

    const text = this.question.trim();
    if (!text && !this.audioBlob) return;

    this.progress = true;
    this.composerHintMessage = '';
    this.autoplayBlockedMessage = '';
    this.stopAnswerAudio();

    // User bubble
    const userText = text || (this.audioBlob ? '🎙️ Message audio' : '');
    this.pushMessage({ role: 'user', text: userText, markdown: false });

    const fd = new FormData();
    if (this.sessionId) fd.append('sessionId', this.sessionId);
    fd.append('format', this.wantAudio ? 'audio' : 'text');
    const hasText = !!text;
    const hasAudio = !!this.audioBlob;
    if (hasText && hasAudio) {
      this.composerHintMessage = 'Texte + audio détectés : envoi du texte (audio ignoré).';
    }
    if (hasText) fd.append('question', text);
    if (!hasText && hasAudio) fd.append('audio', this.audioBlob!, 'question.webm');
    for (const f of this.files) fd.append('files', f, f.name);

    this.question = '';
    this.clearRecordedAudio();
    this.scrollToBottom();

    try {
      if (this.wantAudio) {
        const resp = await firstValueFrom(
          this.http.post(`${this.apiBase}/api/chat`, fd, { observe: 'response', responseType: 'blob' }),
        );
        this.updateSessionId(resp);
        this.pushMessage({ role: 'ai', text: '🔊 Réponse audio', markdown: false });
        await this.playAnswerAudio(resp.body!);
      } else {
        const resp = await firstValueFrom(
          this.http.post<RagAnswer>(`${this.apiBase}/api/chat`, fd, { observe: 'response' }),
        );
        this.updateSessionId(resp);
        const answer = resp.body?.answer ?? '';
        const sources = resp.body?.sources ?? [];
        this.pushMessage({ role: 'ai', text: answer || '(réponse vide)', markdown: true, sources });
        if (resp.body?.transcription) {
          this.pushMessage({ role: 'ai', text: `Transcription: ${resp.body.transcription}`, markdown: false });
        }
      }
    } catch (err: any) {
      const msg = await this.formatHttpError(err);
      this.pushMessage({ role: 'ai', text: `⚠️ ${msg}`, markdown: false });
    } finally {
      this.progress = false;
      this.cd.detectChanges();
      this.scrollToBottom();
    }
  }

  private pushMessage(m: Omit<ChatMessage, 'id'>) {
    this.messages.push({ id: crypto.randomUUID(), ...m });
    this.scrollToBottom();
  }

  private scrollToBottom() {
    setTimeout(() => {
      const el = this.threadRef?.nativeElement;
      if (!el) return;
      el.scrollTop = el.scrollHeight;
    }, 0);
  }

  private updateSessionId(resp: HttpResponse<any>) {
    const sid = resp.headers.get('X-Session-Id') ?? '';
    if (sid) {
      this.sessionId = sid;
      localStorage.setItem('ragSessionId', sid);
    }
  }

  private stopAnswerAudio() {
    this.isSpeaking = false;
    if (this.answerAudio) {
      try {
        this.answerAudio.pause();
        this.answerAudio.currentTime = 0;
      } catch {}
    }
    if (this.answerAudioObjectUrl) {
      try {
        URL.revokeObjectURL(this.answerAudioObjectUrl);
      } catch {}
      this.answerAudioObjectUrl = undefined;
    }
  }

  private async playAnswerAudio(blob: Blob) {
    this.stopAnswerAudio();

    const objectUrl = URL.createObjectURL(blob);
    this.answerAudioObjectUrl = objectUrl;

    if (!this.answerAudio) {
      this.answerAudio = new Audio();
      this.answerAudio.preload = 'auto';
      this.answerAudio.onended = () => {
        this.isSpeaking = false;
        if (this.answerAudioObjectUrl) {
          URL.revokeObjectURL(this.answerAudioObjectUrl);
          this.answerAudioObjectUrl = undefined;
        }
        this.cd.detectChanges();
      };
      this.answerAudio.onpause = () => {
        this.isSpeaking = false;
        this.cd.detectChanges();
      };
    }

    this.answerAudio.src = objectUrl;

    try {
      this.isSpeaking = true;
      await this.answerAudio.play();
    } catch {
      this.isSpeaking = false;
      // Autoplay policies: browsers may block play() if they don't consider it user-initiated.
      this.autoplayBlockedMessage = 'Lecture automatique bloquée par le navigateur.';
    } finally {
      this.cd.detectChanges();
    }
  }

  private async formatHttpError(err: unknown): Promise<string> {
    if (err instanceof HttpErrorResponse) {
      const retryAfter = err.headers?.get('Retry-After');
      if (err.status === 429 && retryAfter) return `Rate limit atteint. Réessaie dans ${retryAfter}s.`;

      // JSON errors from backend: Blob or string
      if (err.error instanceof Blob) {
        try {
          const t = await err.error.text();
          return t || err.message || 'Erreur réseau';
        } catch {
          return err.message || 'Erreur réseau';
        }
      }
      if (typeof err.error === 'string') return err.error || err.message || 'Erreur réseau';
      return err.message || `Erreur HTTP ${err.status}`;
    }
    if (err && typeof err === 'object' && 'message' in (err as any)) {
      return String((err as any).message);
    }
    return 'Erreur';
  }
}
