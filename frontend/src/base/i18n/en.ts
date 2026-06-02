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

export default {
  common: {
    confirm: 'Confirm',
    cancel: 'Cancel',
    save: 'Save',
    delete: 'Delete',
    edit: 'Edit',
    add: 'Add',
    search: 'Search',
    loading: 'Loading...',
    error: 'Error',
    success: 'Success',
    warning: 'Warning',
    info: 'Info'
  },
  home: {
    title: 'Campus Smart Service Center',
    subtitle: 'Multi-Agent Assistant',
    description: 'Welcome to the campus smart service assistant. I can help students and staff with policy consultation, service applications, reservations, record inquiries, and feedback or complaints.',
    startChat: 'Start Chat',
    features: {
      title: 'Service Features',
      consult: 'Policy Consultation',
      order: 'Service Handling',
      feedback: 'Feedback & Complaints',
      support: 'Campus Services'
    }
  },
  chat: {
    title: 'Campus Smart Service Assistant',
    placeholder: 'Please enter your question...',
    send: 'Send',
    clear: 'Clear Chat',
    settings: 'Settings',
    thinking: 'AI is thinking...',
    error: 'Send failed, please try again',
    welcome: 'Hello! I am the campus smart service assistant. I can help with policy consultation, campus services, reservations, and feedback. How can I help you?',
    examples: {
      title: 'Common Questions Examples',
      menu: 'What materials are needed for scholarship application?',
      order: 'Book a library discussion room for tomorrow afternoon',
      price: 'Check my campus service records',
      feedback: 'I want to report slow dorm repair service'
    }
  },
  settings: {
    title: 'System Settings',
    apiConfig: {
      title: 'API Configuration',
      baseUrl: 'Backend Service URL',
      baseUrlPlaceholder: 'Please enter backend service URL, e.g.: http://localhost:10000',
      testConnection: 'Test Connection',
      connectionSuccess: 'Connection successful',
      connectionFailed: 'Connection failed'
    },
    userConfig: {
      title: 'User Configuration',
      userId: 'User ID',
      userIdPlaceholder: 'Please enter user ID',
      chatId: 'Chat ID',
      chatIdPlaceholder: 'Please enter chat ID (optional, leave empty for auto-generation)'
    }
  }
}

