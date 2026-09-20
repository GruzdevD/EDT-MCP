export type TabType = 'quickstart' | 'eclipse' | 'debug' | 'proxy' | 'specter' | 'ai';

export interface DebugConfigTemplate {
  id: string;
  name: string;
  type: string;
  description: string;
  template: string;
  filename: string;
}

export interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  timestamp: string;
}
