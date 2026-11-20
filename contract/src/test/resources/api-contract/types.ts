export type SessionStatus = 'pending' | 'active' | 'completed' | 'error' | 'interrupted' | 'deleted';

export type SessionState = 'starting' | 'ready' | 'blocked' | 'attention' | 'closed';

export type TurnState = 'working' | 'done' | 'blocked';

export type DisplayState = 'starting' | 'working' | 'done' | 'blocked' | 'error' | 'closed';

export type BlockerKind =
  | 'trust_prompt'
  | 'trust_prompt_confirmed'
  | 'login_required'
  | 'permission_prompt'
  | 'choice_prompt'
  | 'not_registered'
  | 'unknown';

export interface BlockerOption {
  key: string;
  label: string;
}

export interface Blocker {
  kind: BlockerKind;
  question: string;
  options: BlockerOption[];
}

export type SessionKind =
  | 'conversation' | 'subagent' | 'supervisor' | 'computer' | 'ultrafast' | 'mailverdict';

export interface Session {
  id: string;
  session_type: string;
  model?: string | null;
  live_model?: string | null;
  pending_model?: string | null;
  ultrafast_model?: string | null;
  thinking?: string | null;
  no_memory?: boolean;
  closed_unexpectedly?: boolean;
  status: SessionStatus;
  state?: SessionState | null;
  blocker?: Blocker | null;
  turn_state?: TurnState | null;
  display_state?: DisplayState | null;
  secret_grantable?: boolean;
  secret_prompt?: SecretPrompt | null;
  title: string | null;
  title_locked?: boolean;
  pinned_at?: string | null;
  initial_message: string | null;
  session_tokens: number;
  activity_counts?: ActivityCounts | null;
  claude_session_id?: string | null;
  idle_timeout_minutes?: number | null;
  effective_idle_timeout_minutes?: number | null;
  cse_id?: string | null;
  transcript_path?: string | null;
  created_at: string | null;
  updated_at: string | null;
  last_activity_at: string | null;
  working_dir: string | null;
  agent?: string;
  kind?: SessionKind;
  parent_session_id?: string | null;
  subagent_name?: string | null;
  subagent_type?: string | null;
  subagent_model?: string | null;
  subagent_description?: string | null;
  remote_control?: boolean;
  discovered?: boolean;
  source_missing?: boolean;
  git_branch?: string | null;
  claude_version?: string | null;
  project_id?: string | null;
  phase_id?: string | null;
  project_name?: string | null;
  phase_name?: string | null;
  task_id?: string | null;
  read_position_message_id?: number | null;
  read_position_offset_px?: number | null;
  read_position_at_bottom?: boolean | null;
  read_position_saved_at?: string | null;
  transferred_to_session_id?: string | null;
  transferred_at?: string | null;
  namespace?: string | null;
}

export interface SessionSearchResult extends Session {
  score: number | null;
  tier: 1 | 2 | null;
}

export interface Message {
  id: number;
  session_id: string;
  type: 'user' | 'assistant' | 'tool_result' | 'system';
  subtype: string | null;
  outbox_id?: number | null;
  timestamp: string | null;
  content: string | null;
  thinking: string | null;
  tool_calls: ToolCall[] | null;
  tool_result: ToolResult | null;
  hook_summary: HookSummary | null;
  tokens: TokenUsage | null;
  origin?: string | null;
  origin_meta?: Record<string, string> | null;
  notification_marker?: string | null;
  created_at: string | null;
}

export interface ToolCall {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

export interface ToolResult {
  tool_use_id: string;
  tool_name: string;
  content: string;
  is_error: boolean;
  bash_edit_diff?: BashEditDiff;
}

export interface BashEditDiffHunk {
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  lines: string[];
}

export interface BashEditDiffFile {
  filePath: string;
  created?: boolean;
  deleted?: boolean;
  hunks: BashEditDiffHunk[];
}

export interface BashEditDiff {
  files: BashEditDiffFile[];
  moreFiles: number;
  changedFiles?: string[];
  unavailable?: boolean;
  shared?: boolean;
  skipped?: boolean;
}

export interface HookSummary {
  hook_names: string[];
  has_errors: boolean;
  errors: string[];
  prevented_continuation: boolean;
}

export interface TokenUsage {
  input_tokens?: number;
  output_tokens?: number;
  cache_creation_input_tokens?: number;
  cache_read_input_tokens?: number;
  [key: string]: unknown;
}

export interface MessageFindResult {
  message_ids: number[];
  total: number;
  as_of_id: number | null;
  capped: boolean;
}

export interface SessionType {
  id: string;
  name: string;
  icon: string;
  working_dir?: string;
}

export interface SkillEntry {
  name: string;
  description: string;
}

export interface SkillListing {
  skills: SkillEntry[];
  updated_at: string | null;
}

export interface DraftAttachment {
  id: string;
  filename: string;
  path: string;
  size: number;
  content_type: string | null;
  state: 'uploading' | 'stored' | 'failed' | 'unclaimed';
  created_at: string;
}

export interface Draft {
  key: string;
  text: string;
  version: number;
  device_id: string | null;
  session_type: string | null;
  working_dir: string | null;
  model: string | null;
  thinking: string | null;
  no_memory?: boolean;
  updated_at: string | null;
  previous_text: string;
  attachments: DraftAttachment[];
}

export interface DraftWriteResult {
  key: string;
  version: number;
  previous_text: string;
}

export interface DraftDiscardResult {
  key: string;
  version: number | null;
  previous_text: string;
}

export type VoiceSttMode = 'live' | 'batch';

export type VoiceTakeState = 'open' | 'sealed' | 'resolving' | 'done' | 'failed';

export interface UsagePace {
  line_percent: number;
  delta_points: number;
  level: UsagePaceLevel;
}

export type UsagePaceLevel = 'on_pace' | 'slightly_over' | 'over' | 'far_over';

export interface UsageWindow {
  utilization: number;
  resets_at: string | null;
  pace?: UsagePace | null;
}

export interface ScopedUsageWindow {
  model: string;
  utilization: number;
  resets_at: string | null;
  pace?: UsagePace | null;
}

export interface Usage {
  five_hour?: UsageWindow | null;
  seven_day?: UsageWindow | null;
  seven_day_models?: ScopedUsageWindow[];
  reported_at?: string;
}

export type SessionEngine = 'claude' | 'hermes';

export interface SessionModelInfo {
  id: string;
  effort_levels: string[];
  label: string;
  engine: SessionEngine;
}

export interface SessionModelsResponse {
  models: SessionModelInfo[];
  fast_default_model: string;
  fast_default_thinking: string;
  ultrafast: ApiSessionCatalog;
  mailverdict: ApiSessionCatalog;
}

export interface ApiSessionModelInfo {
  id: string;
  provider: string;
  effort_levels: string[];
}

export interface ApiSessionCatalog {
  default_model: string;
  default_thinking: string;
  models: ApiSessionModelInfo[];
}

export interface SecretPrompt {
  at: string;
  names: string[];
  vault4: string[];
  reason: string | null;
}

export interface ActivityCounts {
  agents: number;
  tasks: number;
}

export interface SseInitEvent {
  entries: Message[];
  cursor: number | null;
  has_more: boolean;
  session_tokens: number | null;
}

export interface SseBatchEvent {
  entries: Message[];
  session_tokens: number | null;
}

export interface PendingSend {
  id: number;
  text: string;
  held?: boolean;
  take_id?: string | null;
}

export interface SseStatusEvent {
  status: SessionStatus;
  state?: SessionState | null;
  blocker?: Blocker | null;
  turn_state?: TurnState | null;
  display_state?: DisplayState | null;
  secret_grantable?: boolean;
  secret_prompt?: SecretPrompt | null;
  activity_counts?: ActivityCounts | null;
  model?: string | null;
  live_model?: string | null;
  pending_model?: string | null;
  ultrafast_model?: string | null;
  thinking?: string | null;
  no_memory?: boolean;
  closed_unexpectedly?: boolean;
  transferred_to_session_id?: string | null;
  transferred_at?: string | null;
  queued?: number;
  queued_texts?: string[];
  pending_sends?: PendingSend[];
  last_error?: string | null;
}

export interface SseProcessingEvent {
  processing: boolean;
  elapsed: number;
}

export interface PostMessageResponse {
  session_id: string;
  message_id: number;
  duplicate?: boolean;
  draft_version?: number | null;
  withdrawn?: boolean;
  text_in_draft?: boolean;
  held?: boolean;
  take_state?: VoiceTakeState;
}

export interface CancelResponse {
  status: 'cancelled';
}

export interface CloseResponse {
  status: 'closed' | 'already_closed' | 'close_error';
  detail?: string;
}

export interface WithdrawnSend extends PendingSend {
  client_message_id: string | null;
}

export interface WithdrawPendingResponse {
  withdrawn: WithdrawnSend[];
  already_delivered: number[];
  unresolved: number[];
  withdrawn_client_ids: string[];
  delivered_client_ids: string[];
  draft_version: number;
}

export interface MoveToBackgroundResponse {
  status: 'nothing_running' | 'moved' | 'refused' | 'unavailable' | 'not_applicable';
  moved: string[];
  not_moved: string[];
  reason?: string;
}

export interface ResumeResponse {
  status: 'resumed' | 'already_running' | 'refused';
  detail?: string;
  session?: Session;
}

export interface DeleteResponse {
  status: 'deleted' | 'already_deleted';
}

export interface AnswerBlockerResponse {
  status: 'ok' | 'no_blocker' | 'error';
  detail?: string;
}

export type NotificationKind = 'agent' | 'alert';

export interface PaiNotification {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  created_at: string;
  read_at: string | null;
  session_id: string | null;
  session_title: string | null;
  anchor: { message_id: number } | null;
  alert: {
    id: string | null;
    key: string;
    severity: string;
    source: string;
    transition: 'raised' | 'cleared';
    active: boolean;
  } | null;
  task_id: string | null;
  task_name: string | null;
  run_slug: string | null;
}

export interface NotificationsResponse {
  unread: number;
  has_more: boolean;
  notifications: PaiNotification[];
}

export interface NotificationSummary {
  unread: number;
}

export interface SseNotificationEvent {
  notification: PaiNotification;
  unread: number;
  in_use: boolean;
}

export interface PushChannelInfo {
  id: string;
  label: string;
  description: string;
  kinds: NotificationKind[];
}

export interface PushChannelsResponse {
  channels: PushChannelInfo[];
}

export interface SignIn {
  id: string;
  kind: 'app' | 'browser' | 'machine';
  name: string | null;
  client_id: string | null;
  user_id: string | null;
  display_name: string | null;
  user_agent: string | null;
  first_seen_at: string;
  last_seen_at: string;
  revoked_at: string | null;
  active: boolean;
  current: boolean;
  agent_slug: string | null;
}

export interface WebPushSubscriptionSummary {
  id: string;
  user_agent: string;
  created_at: string;
  last_seen_at: string;
  last_success_at: string | null;
  muted_channels: string[];
}

export interface WebPushSubscriptionCreated {
  id: string;
  muted_channels: string[];
}

export interface WebPushTestResult {
  delivered: number;
  failed: number;
  removed: number;
}

export type UserRole = 'owner' | 'guest';

export interface NamespaceSummary {
  slug: string;
  display_name: string;
}

export interface MeResponse {
  identity: string;
  role: UserRole;
  user_id: string | null;
  display_name: string | null;
  allowed_session_ids: string[];
  namespaces: NamespaceSummary[];
}

export interface UserSettings {
  recording_mode: VoiceSttMode;
  playback_voice_id: string | null;
  playback_speed: number;
  email_notifications: boolean;
  alarm_ring_interval_s: number;
  alarm_ring_duration_s: number;
  location_sign_in_id: string | null;
}

export interface ArcSseEvent {
  spec_uuid: string;
}

export interface DraftSseEvent {
  key: string;
}

