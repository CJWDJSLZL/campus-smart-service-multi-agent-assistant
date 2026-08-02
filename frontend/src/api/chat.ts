/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import axios from 'axios'
import { useConfigStore } from '@/stores/config'

export interface ChatRequest {
  chat_id: string
  user_id: string
  user_query: string
}

export interface ChatResponse {
  success: boolean
  data?: string
  error?: string
}

export class ChatApiService {
  private configStore = useConfigStore()

  async sendMessage(query: string): Promise<ReadableStream<Uint8Array> | null> {
    try {
      // 构建URL参数，确保正确编码
      const params = new URLSearchParams({
        chat_id: this.configStore.chatId,
        user_id: this.configStore.userId || 'demo-user',
        user_query: query
      })

      const url = `${this.configStore.apiUrl}?${params}`
      console.log('API Request URL:', url)
      console.log('Request params:', {
        chat_id: this.configStore.chatId,
        user_id: this.configStore.userId || 'demo-user',
        user_query: query
      })

      const response = await fetch(url, {
        method: 'GET',
        mode: 'cors',
        credentials: 'omit',
        headers: {
          'Accept': 'text/event-stream',
          'Content-Type': 'application/json',
        }
      })

      console.log('Response status:', response.status)
      console.log('Response headers:', Object.fromEntries(response.headers.entries()))

      if (!response.ok) {
        const errorText = await response.text()
        console.error('API Error Response:', errorText)
        throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`)
      }

      if (!response.body) {
        throw new Error('No response body')
      }

      return response.body
    } catch (error) {
      console.error('Chat API error:', error)
      throw error
    }
  }

  async testConnection(): Promise<boolean> {
    try {
      const response = await axios.get(`${this.configStore.baseUrl}/health`, {
        timeout: 5000
      })
      return response.status === 200
    } catch (error) {
      console.error('Connection test failed:', error)
      return false
    }
  }

  /**
   * Human-in-the-Loop 确认接口。
   * 用户对 Agent 生成的执行计划进行确认（approve）或拒绝（reject）。
   * 调用 order-sub-agent 的 /confirm 端点，通过相同 chat_id resume 暂停的 Graph。
   */
  async confirmAction(chatId: string, action: 'approve' | 'reject'): Promise<ReadableStream<Uint8Array> | null> {
    try {
      const baseUrl = this.configStore.baseUrl.replace('/api/assistant', '')
      const params = new URLSearchParams({ chat_id: chatId, action })
      const url = `${baseUrl}/api/order-sub-agent/confirm?${params}`
      console.log('Confirm URL:', url)

      const response = await fetch(url, {
        method: 'GET',
        mode: 'cors',
        credentials: 'omit',
        headers: { 'Accept': 'text/event-stream' }
      })

      if (!response.ok) {
        throw new Error(`Confirm failed: ${response.status}`)
      }

      return response.body
    } catch (error) {
      console.error('Confirm API error:', error)
      throw error
    }
  }
}

export const chatApiService = new ChatApiService()

