// DOM 元素引用
const logEl = document.getElementById('log');
const connectBtn = document.getElementById('connectBtn');
const disconnectBtn = document.getElementById('disconnectBtn');
const speakBtn = document.getElementById('speakBtn');
const endSpeakBtn = document.getElementById('endSpeakBtn');
const chatMessages = document.getElementById('chatMessages');
const audioPlayer = document.getElementById('audioPlayer');
const statusContainer = document.getElementById('statusContainer');
const volumeMeter = document.getElementById('volumeMeter');
const volumeBar = document.getElementById('volumeBar');
const volumeStatus = document.getElementById('volumeStatus');
const volumeThresholdInput = document.getElementById('volumeThreshold');
const thresholdValue = document.getElementById('thresholdValue');
const volumeThresholdLine = document.getElementById('volumeThresholdLine');

// 全局变量
let ws;
let audioContext;
let analyser;
let microphone;
let scriptProcessor;
let audioChunks = [];
let volumeThreshold = 5; // 音量阈值百分比
let isSpeaking = false;
let audioStream;
let pcmBuffer = [];
let currentUserMessageId = null; // 当前用户消息ID
let currentAssistantMessageId = null; // 当前助手消息ID（用于加载状态）
let currentAssistantAudioMessageId = null; // 当前助手音频消息ID
let assistantAudioChunks = []; // 助手音频数据
let assistantAudioBlob = null; // 累积的音频Blob
let assistantTextContent = ''; // 助手文字内容（用于流式显示）
let isAudioComplete = false; // 音频是否接收完成
let audioCompleteTimer = null; // 音频完成检测定时器
let lastAudioChunkTime = 0; // 最后一次收到音频的时间
let isSessionActive = false; // 会话是否激活

// 初始化阈值线位置
volumeThresholdLine.style.left = volumeThreshold + '%';

// 更新阈值
volumeThresholdInput.addEventListener('input', (e) => {
  volumeThreshold = parseInt(e.target.value);
  thresholdValue.textContent = volumeThreshold;
  volumeThresholdLine.style.left = volumeThreshold + '%';
});

function log(msg) {
  const time = new Date().toLocaleTimeString();
  logEl.textContent += `\n[${time}] ${msg}`;
  logEl.scrollTop = logEl.scrollHeight;
}

function showStatus(msg, type = 'info') {
  statusContainer.innerHTML = `<div class="status ${type}">${msg}</div>`;
  setTimeout(() => {
    if (statusContainer.firstChild && statusContainer.firstChild.textContent === msg) {
      statusContainer.innerHTML = '';
    }
  }, 5000);
}

// 清除欢迎消息
function clearWelcomeMessage() {
  const welcomeMsg = chatMessages.querySelector('.welcome-message');
  if (welcomeMsg) {
    welcomeMsg.remove();
  }
}

// 添加用户消息到聊天界面
function addUserMessage(text) {
  clearWelcomeMessage();
  const messageId = 'msg-' + Date.now();
  currentUserMessageId = messageId;
  
  const messageDiv = document.createElement('div');
  messageDiv.className = 'message user';
  messageDiv.id = messageId;
  messageDiv.innerHTML = `
    <div class="message-content">
      <div>${escapeHtml(text)}</div>
      <div class="message-time">${new Date().toLocaleTimeString()}</div>
    </div>
  `;
  
  chatMessages.appendChild(messageDiv);
  scrollToBottom();
}

// 显示助手正在响应中的loading
function showAssistantLoading() {
  clearWelcomeMessage();
  
  // 移除之前的loading（如果存在）
  const existingLoading = chatMessages.querySelector('.message.loading');
  if (existingLoading) {
    existingLoading.remove();
  }
  
  const messageId = 'loading-' + Date.now();
  currentAssistantMessageId = messageId;
  
  const messageDiv = document.createElement('div');
  messageDiv.className = 'message assistant loading';
  messageDiv.id = messageId;
  messageDiv.innerHTML = `
    <div class="message-content">
      <span>对方正在响应中</span>
      <div class="loading-dots">
        <div class="loading-dot"></div>
        <div class="loading-dot"></div>
        <div class="loading-dot"></div>
      </div>
    </div>
  `;
  
  chatMessages.appendChild(messageDiv);
  scrollToBottom();
}

// 创建或更新助手音频消息
// audioComplete: true表示音频接收完成，false表示还在接收中
function createOrUpdateAssistantAudioMessage(audioBlob, audioComplete = false) {
  // 移除loading
  const loadingMsg = chatMessages.querySelector('.message.loading');
  if (loadingMsg) {
    loadingMsg.remove();
  }
  
  // 如果消息已存在，更新音频URL和文字内容
  if (currentAssistantAudioMessageId) {
    const existingMsg = document.getElementById(currentAssistantAudioMessageId);
    if (existingMsg) {
      // 释放旧的URL（如果存在）
      const oldAudio = existingMsg.querySelector('.message-audio');
      if (oldAudio && oldAudio.dataset.audioUrl) {
        try {
          URL.revokeObjectURL(oldAudio.dataset.audioUrl);
        } catch (e) {
          // 忽略URL释放错误
        }
      }
      // 创建新的URL（使用最新的完整audioBlob）
      // 注意：audioBlob应该包含所有累积的音频chunks
      const audioUrl = URL.createObjectURL(audioBlob);
      oldAudio.dataset.audioUrl = audioUrl;
      oldAudio.setAttribute('onclick', `playAudioMessage('${audioUrl}', this)`);
      
      // 更新音频按钮状态（从"生成中"变为"点击播放"）
      const audioIcon = oldAudio.querySelector('.audio-icon');
      const audioDuration = oldAudio.querySelector('.audio-duration');
      if (audioIcon && (audioIcon.textContent === '⏳' || audioIcon.textContent.trim() === '')) {
        audioIcon.textContent = '🔊';
      }
      if (audioDuration && (audioDuration.textContent === '生成中...' || audioDuration.textContent.trim() === '')) {
        audioDuration.textContent = '点击播放';
      }
      if (oldAudio.style.opacity === '0.5') {
        oldAudio.style.opacity = '1';
      }
      if (oldAudio.style.cursor === 'default') {
        oldAudio.style.cursor = 'pointer';
      }
      
      // 更新文字显示按钮和文字内容
      updateTextDisplayButton(existingMsg, audioComplete);
      
      scrollToBottom();
      return;
    }
  }
  
  // 创建新消息
  // 首先检查是否已经有临时消息（通过文字创建的"生成中"消息）
  let tempMsg = null;
  if (currentAssistantAudioMessageId) {
    tempMsg = document.getElementById(currentAssistantAudioMessageId);
    if (tempMsg) {
      const tempIcon = tempMsg.querySelector('.audio-icon');
      if (tempIcon && tempIcon.textContent === '⏳') {
        // 这是临时消息，直接更新它
        const audioUrl = URL.createObjectURL(audioBlob);
        const oldAudio = tempMsg.querySelector('.message-audio');
        if (oldAudio) {
          oldAudio.dataset.audioUrl = audioUrl;
          oldAudio.setAttribute('onclick', `playAudioMessage('${audioUrl}', this)`);
          oldAudio.style.opacity = '1';
          oldAudio.style.cursor = 'pointer';
          const icon = oldAudio.querySelector('.audio-icon');
          const duration = oldAudio.querySelector('.audio-duration');
          if (icon) icon.textContent = '🔊';
          if (duration) duration.textContent = '点击播放';
        }
        scrollToBottom();
        return;
      }
    }
  }
  
  // 如果没有临时消息，创建新消息
  const messageId = 'msg-assistant-' + Date.now();
  currentAssistantAudioMessageId = messageId;
  const audioUrl = URL.createObjectURL(audioBlob);
  
  const messageDiv = document.createElement('div');
  messageDiv.className = 'message assistant';
  messageDiv.id = messageId;
  messageDiv.innerHTML = `
    <div class="message-content">
      <div class="message-audio" onclick="playAudioMessage('${audioUrl}', this)" data-audio-url="${audioUrl}">
        <span class="audio-icon">🔊</span>
        <span class="audio-duration">${audioComplete ? '点击播放' : '生成中...'}</span>
      </div>
      ${audioComplete && assistantTextContent ? `<div class="message-text-toggle" onclick="toggleTextDisplay('${messageId}')">
        <span class="text-toggle-icon">📝</span>
        <span class="text-toggle-text">显示原文</span>
      </div>` : ''}
      <div class="message-text" id="text-${messageId}" style="display: none;">
        ${escapeHtml(assistantTextContent)}
      </div>
      <div class="message-time">${new Date().toLocaleTimeString()}</div>
    </div>
  `;
  
  chatMessages.appendChild(messageDiv);
  scrollToBottom();
  currentAssistantMessageId = null;
}

// 当前正在播放的音频对象
let currentPlayingAudio = null;

// 播放音频消息
// 注意：当前使用Blob URL，需要完整的音频数据才能播放，不支持流式播放
// 要实现流式播放，需要使用MediaSource API，但MP3格式可能不完全支持
// 当前实现：等待音频Blob完整后再播放
function playAudioMessage(audioUrl, element) {
  // 如果正在播放其他音频，先停止
  if (currentPlayingAudio && !currentPlayingAudio.paused) {
    currentPlayingAudio.pause();
    currentPlayingAudio.currentTime = 0;
    // 恢复之前的UI
    const prevIcon = document.querySelector('.audio-icon[data-playing="true"]');
    const prevDuration = document.querySelector('.audio-duration[data-playing="true"]');
    if (prevIcon) {
      prevIcon.textContent = '🔊';
      prevIcon.removeAttribute('data-playing');
    }
    if (prevDuration) {
      prevDuration.textContent = '点击播放';
      prevDuration.removeAttribute('data-playing');
    }
  }
  
  // 检查音频是否还在接收中
  if (!isAudioComplete) {
    log('⚠️ 音频还在接收中，请稍候再播放');
    showStatus('音频还在生成中，请稍候...', 'info');
    return;
  }
  
  const audio = new Audio(audioUrl);
  const icon = element.querySelector('.audio-icon');
  const duration = element.querySelector('.audio-duration');
  
  currentPlayingAudio = audio;
  
  // 更新UI
  icon.textContent = '⏸️';
  icon.setAttribute('data-playing', 'true');
  duration.textContent = '播放中...';
  duration.setAttribute('data-playing', 'true');
  
  audio.onended = () => {
    icon.textContent = '🔊';
    icon.removeAttribute('data-playing');
    duration.textContent = '点击播放';
    duration.removeAttribute('data-playing');
    currentPlayingAudio = null;
  };
  
  audio.onpause = () => {
    icon.textContent = '🔊';
    icon.removeAttribute('data-playing');
    duration.textContent = '点击播放';
    duration.removeAttribute('data-playing');
  };
  
  audio.onerror = () => {
    icon.textContent = '❌';
    icon.removeAttribute('data-playing');
    duration.textContent = '播放失败';
    duration.removeAttribute('data-playing');
    currentPlayingAudio = null;
  };
  
  audio.play().catch(err => {
    log('播放音频失败: ' + err.message);
    icon.textContent = '❌';
    icon.removeAttribute('data-playing');
    duration.textContent = '播放失败';
    duration.removeAttribute('data-playing');
    currentPlayingAudio = null;
  });
}

// HTML转义
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// 更新助手文字内容（流式显示）
function updateAssistantText(text) {
  // 如果消息已存在，更新文字内容
  if (currentAssistantAudioMessageId) {
    const msgDiv = document.getElementById(currentAssistantAudioMessageId);
    if (msgDiv) {
      let textDiv = msgDiv.querySelector('.message-text');
      if (textDiv) {
        // 更新文字内容，保持 streaming 类（如果存在）
        textDiv.innerHTML = escapeHtml(text);
        if (!textDiv.classList.contains('streaming')) {
          textDiv.classList.add('streaming');
        }
      } else {
        // 如果文字区域不存在，创建它
        const messageContent = msgDiv.querySelector('.message-content');
        if (messageContent) {
          const audioDiv = msgDiv.querySelector('.message-audio');
          textDiv = document.createElement('div');
          textDiv.className = 'message-text streaming';
          textDiv.innerHTML = escapeHtml(text);
          if (audioDiv && audioDiv.nextSibling) {
            messageContent.insertBefore(textDiv, audioDiv.nextSibling);
          } else {
            messageContent.appendChild(textDiv);
          }
        }
      }
      scrollToBottom();
      return;
    }
  }
  
  // 如果消息还不存在，创建一个临时的文字消息（音频会在收到时更新）
  // 这样可以确保文字能够立即显示
  if (!currentAssistantAudioMessageId && text && text.trim()) {
    const messageId = 'msg-assistant-' + Date.now();
    currentAssistantAudioMessageId = messageId;
    
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message assistant';
    messageDiv.id = messageId;
    messageDiv.innerHTML = `
      <div class="message-content">
        <div class="message-audio" style="opacity: 0.5; cursor: default;">
          <span class="audio-icon">⏳</span>
          <span class="audio-duration">生成中...</span>
        </div>
        <div class="message-text streaming">${escapeHtml(text)}</div>
        <div class="message-time">${new Date().toLocaleTimeString()}</div>
      </div>
    `;
    
    chatMessages.appendChild(messageDiv);
    scrollToBottom();
  }
}

// 更新文字显示按钮
function updateTextDisplayButton(msgDiv, audioComplete) {
  if (!msgDiv) return;
  
  const messageId = msgDiv.id;
  const messageContent = msgDiv.querySelector('.message-content');
  if (!messageContent) return;
  
  // 查找或创建文字显示按钮
  let textToggle = msgDiv.querySelector('.message-text-toggle');
  const textDiv = msgDiv.querySelector('.message-text');
  
  // 检查该消息的文字是否已显示（通过data属性）
  const textVisible = msgDiv.dataset.textVisible === 'true';
  
  if (audioComplete && assistantTextContent && !textVisible) {
    // 音频完成且有文字内容，显示"显示原文"按钮
    if (!textToggle) {
      textToggle = document.createElement('div');
      textToggle.className = 'message-text-toggle';
      textToggle.onclick = () => toggleTextDisplay(messageId);
      textToggle.innerHTML = `
        <span class="text-toggle-icon">📝</span>
        <span class="text-toggle-text">显示原文</span>
      `;
      const audioDiv = msgDiv.querySelector('.message-audio');
      if (audioDiv && audioDiv.nextSibling) {
        messageContent.insertBefore(textToggle, audioDiv.nextSibling);
      } else {
        messageContent.appendChild(textToggle);
      }
    }
    
    // 确保文字区域存在但隐藏
    if (!textDiv) {
      const newTextDiv = document.createElement('div');
      newTextDiv.className = 'message-text';
      newTextDiv.id = `text-${messageId}`;
      newTextDiv.style.display = 'none';
      newTextDiv.innerHTML = escapeHtml(assistantTextContent);
      messageContent.appendChild(newTextDiv);
    } else {
      textDiv.style.display = 'none';
      textDiv.innerHTML = escapeHtml(assistantTextContent);
    }
  } else if (textVisible && textDiv) {
    // 文字已显示，隐藏按钮
    if (textToggle) {
      textToggle.remove();
    }
    textDiv.style.display = 'block';
  }
}

// 切换文字显示/隐藏
function toggleTextDisplay(messageId) {
  const msgDiv = document.getElementById(messageId);
  if (!msgDiv) return;
  
  const textDiv = msgDiv.querySelector('.message-text');
  const textToggle = msgDiv.querySelector('.message-text-toggle');
  
  if (textDiv) {
    const isCurrentlyVisible = textDiv.style.display !== 'none' && textDiv.style.display !== '';
    
    if (!isCurrentlyVisible) {
      // 显示文字
      textDiv.style.display = 'block';
      msgDiv.dataset.textVisible = 'true';
      if (textToggle) {
        textToggle.querySelector('.text-toggle-text').textContent = '隐藏原文';
      }
    } else {
      // 隐藏文字
      textDiv.style.display = 'none';
      msgDiv.dataset.textVisible = 'false';
      if (textToggle) {
        textToggle.querySelector('.text-toggle-text').textContent = '显示原文';
      }
    }
  }
}

// 滚动到底部
function scrollToBottom() {
  chatMessages.scrollTop = chatMessages.scrollHeight;
}

// 计算音频音量
function getAudioVolume(dataArray) {
  let sum = 0;
  for (let i = 0; i < dataArray.length; i++) {
    sum += dataArray[i];
  }
  const average = sum / dataArray.length;
  return (average / 255) * 100; // 转换为百分比
}

// 连接到服务器
async function connect() {
  try {
    log('正在连接服务器...');
    ws = new WebSocket('ws://localhost:8080/ws/asr');
    ws.binaryType = 'arraybuffer';
    
    ws.onopen = () => {
      log('✓ WebSocket 已连接');
      showStatus('已连接到服务器，可以开始说话', 'success');
      connectBtn.disabled = true;
      disconnectBtn.disabled = false;
      speakBtn.disabled = false;
      audioChunks = [];
      assistantAudioChunks = [];
      assistantAudioBlob = null;
      assistantTextContent = '';
      currentAssistantAudioMessageId = null;
      currentUserMessageId = null;
      isSessionActive = true;
      clearWelcomeMessage();
    };
    
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        try {
          const msg = JSON.parse(ev.data);
          log(`📨 ${msg.type}: ${JSON.stringify(msg).substring(0, 100)}`);
          
          switch(msg.type) {
            case 'connected':
              log(`✓ 会话ID: ${msg.sessionId}`);
              isSessionActive = true;
              break;
            case 'transcript':
              log(`📝 识别结果: ${msg.text}`);
              showStatus(`识别: ${msg.text}`, 'info');
              // 更新用户消息显示（如果存在）
              if (currentUserMessageId) {
                const userMsg = document.getElementById(currentUserMessageId);
                if (userMsg) {
                  const content = userMsg.querySelector('.message-content > div');
                  if (content) {
                    content.textContent = msg.text;
                  }
                }
              }
              break;
            case 'intent':
              log(`🎯 意图: ${msg.value}`);
              showStatus(`意图识别: ${msg.value === 'order' ? '下单' : '闲聊'}`, 'info');
              // 重置助手音频消息ID和文字内容，准备创建新的消息
              currentAssistantAudioMessageId = null;
              assistantAudioChunks = []; // 清空音频chunks数组，确保从空开始
              assistantAudioBlob = null;
              assistantTextContent = ''; // 重置文字内容
              isAudioComplete = false; // 重置音频完成标志
              log(`🔄 重置音频缓冲区，准备接收新的音频数据`);
              // 显示助手正在响应
              showAssistantLoading();
              break;
            case 'text_chunk':
              // 流式接收文字内容（但不立即显示，等音频完成后用户点击才显示）
              if (msg.text) {
                assistantTextContent += msg.text;
                // 不调用 updateAssistantText，文字内容暂存，等用户点击显示
                log(`📝 收到文字块: ${msg.text.substring(0, 20)}... (暂存，待音频完成后显示)`);
              }
              break;
            case 'complete':
              log('✓ LLM处理完成，等待TTS音频完成');
              showStatus('处理完成，等待音频生成', 'info');
              speakBtn.disabled = false;
              // 移除loading（如果还存在）
              const loadingMsg2 = chatMessages.querySelector('.message.loading');
              if (loadingMsg2) {
                loadingMsg2.remove();
              }
              // 最终确认音频消息（如果还有音频数据）
              if (assistantAudioChunks.length > 0) {
                assistantAudioBlob = new Blob([...assistantAudioChunks], { type: 'audio/mpeg' });
                createOrUpdateAssistantAudioMessage(assistantAudioBlob, false);
                // 如果complete后3秒内没有新音频，自动标记为完成
                if (audioCompleteTimer) {
                  clearTimeout(audioCompleteTimer);
                }
                audioCompleteTimer = setTimeout(() => {
                  if (!isAudioComplete && assistantAudioChunks.length > 0) {
                    isAudioComplete = true;
                    log('✓ 音频接收完成（complete后无新音频）');
                    showStatus('音频生成完成，可以播放', 'success');
                    createOrUpdateAssistantAudioMessage(assistantAudioBlob, true);
                  }
                }, 3000);
              }
              // 注意：不要在这里清理 assistantAudioChunks！
              // 因为 TTS 是异步的，complete 消息到达时，TTS 可能还在继续发送音频数据
              // 清理会在下一轮 intent 消息到达时进行
              // 注意：不重置currentAssistantAudioMessageId，因为消息已经显示
              // 下一轮新消息时会自动创建新的ID
              break;
            case 'error':
              log(`❌ 错误: ${msg.message}`);
              showStatus(`错误: ${msg.message}`, 'error');
              speakBtn.disabled = false;
              // 移除loading
              const loadingMsg = chatMessages.querySelector('.message.loading');
              if (loadingMsg) {
                loadingMsg.remove();
              }
              break;
          }
        } catch (e) {
          log('WS 文本: ' + ev.data);
        }
      } else {
        // 接收音频数据（TTS生成的音频）
        try {
          const audioData = new Uint8Array(ev.data);
          if (audioData.length > 0) {
            const isFirstChunk = assistantAudioChunks.length === 0;
            const prevChunksCount = assistantAudioChunks.length;
            
            // 确保所有音频数据都被保存（包括第一个chunk）
            // 使用 Uint8Array 的副本，避免引用问题
            const audioDataCopy = new Uint8Array(audioData);
            assistantAudioChunks.push(audioDataCopy);
            
            // 验证chunk确实被添加了
            if (assistantAudioChunks.length !== prevChunksCount + 1) {
              log(`⚠️ 警告：音频chunk添加后数量异常！期望: ${prevChunksCount + 1}, 实际: ${assistantAudioChunks.length}`);
            }
            
            log(`🔊 收到音频数据: ${audioData.length} bytes (累计: ${assistantAudioChunks.length} 段)${isFirstChunk ? ' [第一个chunk]' : ''}`);
            
            // 更新最后接收时间
            lastAudioChunkTime = Date.now();
            
            // 清除之前的完成检测定时器
            if (audioCompleteTimer) {
              clearTimeout(audioCompleteTimer);
              audioCompleteTimer = null;
            }
            
            // 实时更新音频Blob并显示
            // 使用数组的副本创建Blob，确保所有数据都被包含
            // 注意：不要在这里清空 assistantAudioChunks，它会持续累积直到下一轮 intent
            assistantAudioBlob = new Blob([...assistantAudioChunks], { type: 'audio/mpeg' });
            createOrUpdateAssistantAudioMessage(assistantAudioBlob, false); // false表示音频还在接收中
            
            // 验证Blob大小
            const expectedSize = assistantAudioChunks.reduce((sum, chunk) => sum + chunk.length, 0);
            if (assistantAudioBlob.size !== expectedSize) {
              log(`⚠️ 警告：Blob大小不匹配！期望: ${expectedSize}, 实际: ${assistantAudioBlob.size}`);
            }
            
            log(`✅ 音频消息已更新，总大小: ${assistantAudioBlob.size} bytes (chunks: ${assistantAudioChunks.length})`);
            
            // 设置音频完成检测：如果3秒内没有新音频，认为音频接收完成
            audioCompleteTimer = setTimeout(() => {
              if (!isAudioComplete && assistantAudioChunks.length > 0) {
                isAudioComplete = true;
                log('✓ 音频接收完成');
                showStatus('音频生成完成，可以播放', 'success');
                // 更新消息，显示"显示文字"按钮
                createOrUpdateAssistantAudioMessage(assistantAudioBlob, true); // true表示音频已完成
              }
            }, 3000); // 3秒无新音频则认为完成
          } else {
            log(`⚠️ 收到空的音频数据`);
          }
        } catch (e) {
          log(`❌ 处理音频数据失败: ${e.message}`);
        }
      }
    };
    
    ws.onclose = () => {
      log('WebSocket 已关闭');
      showStatus('连接已关闭', 'info');
      isSessionActive = false;
      resetButtons();
      // 处理剩余的音频数据
      if (assistantAudioChunks.length > 0 && !currentAssistantAudioMessageId) {
        assistantAudioBlob = new Blob(assistantAudioChunks, { type: 'audio/mpeg' });
        createOrUpdateAssistantAudioMessage(assistantAudioBlob);
      }
      assistantAudioChunks = [];
      assistantAudioBlob = null;
    };
    
    ws.onerror = (e) => {
      log('❌ WebSocket 错误');
      showStatus('WebSocket 连接错误', 'error');
    };

    // 等待连接建立
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('连接超时')), 5000);
      ws.addEventListener('open', () => {
        clearTimeout(timeout);
        resolve();
      }, { once: true });
      ws.addEventListener('error', () => {
        clearTimeout(timeout);
        reject(new Error('连接失败'));
      }, { once: true });
    });
    
  } catch (err) {
    log('❌ 连接失败: ' + err.message);
    showStatus('连接失败: ' + err.message, 'error');
    resetButtons();
  }
}

// 将Float32Array转换为16位PCM
function floatTo16BitPCM(float32Array) {
  const buffer = new ArrayBuffer(float32Array.length * 2);
  const view = new DataView(buffer);
  let offset = 0;
  for (let i = 0; i < float32Array.length; i++, offset += 2) {
    let s = Math.max(-1, Math.min(1, float32Array[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
  }
  return buffer;
}

// 开始说话
async function startSpeaking() {
  try {
    log('正在启动麦克风...');
    audioStream = await navigator.mediaDevices.getUserMedia({ 
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        sampleRate: 16000 // 16k采样率
      } 
    });
    
    // 设置音频上下文（16k采样率）
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: 16000
    });
    
    // 如果实际采样率不是16k，需要重采样
    if (audioContext.sampleRate !== 16000) {
      log(`⚠️ 实际采样率: ${audioContext.sampleRate}Hz，期望16kHz`);
    }
    
    analyser = audioContext.createAnalyser();
    microphone = audioContext.createMediaStreamSource(audioStream);
    scriptProcessor = audioContext.createScriptProcessor(4096, 1, 1);
    
    analyser.smoothingTimeConstant = 0.8;
    analyser.fftSize = 2048;
    
    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    
    // 创建音量分析的连接（用于显示）
    microphone.connect(analyser);
    
    // 创建音频处理连接（用于捕获PCM数据）
    // 连接到analyser作为目标节点，避免音频回环到扬声器
    microphone.connect(scriptProcessor);
    scriptProcessor.connect(analyser); // 连接到analyser而不是destination
    
    let lastSendTime = 0;
    let lastLogTime = 0;
    const sendInterval = 250; // 每250ms发送一次
    const logInterval = 1000; // 每1秒记录一次日志
    
    // 处理音频数据并发送PCM
    scriptProcessor.onaudioprocess = (e) => {
      const inputData = e.inputBuffer.getChannelData(0);
      
      // 计算音量用于显示
      analyser.getByteFrequencyData(dataArray);
      const volume = getAudioVolume(dataArray);
      
      // 更新音量显示
      volumeBar.style.width = volume + '%';
      
      const now = Date.now();
      
      if (volume >= volumeThreshold) {
        volumeStatus.textContent = `音量: ${Math.round(volume)}% ✓ 正在录音`;
        volumeStatus.style.color = '#28a745';
        
        // 将音频数据转换为16位PCM并发送
        if (now - lastSendTime >= sendInterval && ws && ws.readyState === WebSocket.OPEN) {
          const pcmData = floatTo16BitPCM(inputData);
          ws.send(pcmData);
          
          // 减少日志频率
          if (now - lastLogTime >= logInterval) {
            log(`📤 发送PCM音频: ${pcmData.byteLength} bytes (音量: ${Math.round(volume)}%)`);
            lastLogTime = now;
          }
          lastSendTime = now;
        }
      } else {
        volumeStatus.textContent = `音量: ${Math.round(volume)}% (低于阈值 ${volumeThreshold}%)`;
        volumeStatus.style.color = '#6c757d';
        // 只在首次检测到低音量时记录
        if (now - lastLogTime >= logInterval) {
          log(`🔇 跳过低音量音频 (${Math.round(volume)}% < ${volumeThreshold}%)`);
          lastLogTime = now;
        }
      }
    };
    
    isSpeaking = true;
    pcmBuffer = [];
    
    speakBtn.disabled = true;
    endSpeakBtn.disabled = false;
    volumeMeter.style.display = 'block';
    
    log('🎤 开始录音（PCM格式，16kHz，带音量过滤）');
    showStatus('正在录音...', 'info');
    
  } catch (err) {
    log('❌ 启动麦克风失败: ' + err.message);
    showStatus('麦克风启动失败: ' + err.message, 'error');
  }
}

// 结束说话
function endSpeaking() {
  log('正在结束说话...');
  
  if (scriptProcessor) {
    scriptProcessor.disconnect();
    scriptProcessor = null;
  }
  
  if (microphone) {
    microphone.disconnect();
    microphone = null;
  }
  
  if (analyser) {
    analyser.disconnect();
    analyser = null;
  }
  
  if (audioStream) {
    audioStream.getTracks().forEach(track => track.stop());
    audioStream = null;
    log('✓ 录音已停止');
  }
  
  if (audioContext) {
    audioContext.close().catch(err => log('关闭音频上下文失败: ' + err.message));
    audioContext = null;
  }
  
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send('END');
    log('📤 发送结束信号，等待服务器处理...');
    showStatus('正在处理您的请求...', 'warning');
    // 显示用户消息（使用临时文本，后续会被识别结果替换）
    addUserMessage('🎤 语音消息...');
  }
  
  isSpeaking = false;
  speakBtn.disabled = true; // 等待处理完成
  endSpeakBtn.disabled = true;
  volumeMeter.style.display = 'none';
  pcmBuffer = [];
}

// 断开连接
function disconnect() {
  log('正在关闭会话...');
  
  if (isSpeaking) {
    endSpeaking();
  }
  
  // 确保所有资源都被清理
  if (audioStream) {
    audioStream.getTracks().forEach(track => track.stop());
    audioStream = null;
  }
  
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.close();
  }
  
  resetButtons();
  showStatus('会话已关闭', 'info');
  log('✓ 会话已关闭');
}

function resetButtons() {
  connectBtn.disabled = false;
  disconnectBtn.disabled = true;
  speakBtn.disabled = true;
  endSpeakBtn.disabled = true;
  volumeMeter.style.display = 'none';
  isSessionActive = false;
  currentUserMessageId = null;
  currentAssistantMessageId = null;
  currentAssistantAudioMessageId = null;
  assistantAudioChunks = [];
  assistantAudioBlob = null;
}

// 绑定事件处理器
connectBtn.onclick = connect;
disconnectBtn.onclick = disconnect;
speakBtn.onclick = startSpeaking;
endSpeakBtn.onclick = endSpeaking;

// 将播放函数暴露到全局，以便在HTML中调用
window.playAudioMessage = playAudioMessage;

// 初始化
log('准备就绪，点击"开始会话"连接到服务器');

