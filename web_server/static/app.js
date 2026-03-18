const state = {
  config: {
    apiBase: "/api",
    assetBase: "/assets/lost_cities_v3",
  },
  serverInfo: null,
  agents: [],
  users: [],
  challenges: [],
  announcements: [],
  matches: [],
  currentMatch: null,
  currentReplay: null,
  currentSimulation: null,
  replayCursor: 0,
  boardSelection: {
    cardId: "",
  },
  session: {
    playerName: localStorage.getItem("lostCities.playerName") || "",
    userId: localStorage.getItem("lostCities.userId") || "",
    userToken: localStorage.getItem("lostCities.userToken") || "",
    currentMatchId: localStorage.getItem("lostCities.currentMatchId") || "",
  },
};

const dom = {};

document.addEventListener("DOMContentLoaded", () => {
  bindDom();
  bindEvents();
  bootstrapUi();
});

function bindDom() {
  const ids = [
    "server-status",
    "server-url",
    "connected-users",
    "agent-list",
    "refresh-agents",
    "agent-upload-form",
    "agent-upload-status",
    "agent-zip",
    "agent-name",
    "refresh-all",
    "player-name",
    "use-purple",
    "join-lobby",
    "refresh-lobby",
    "session-status",
    "challenge-form",
    "challenge-mode",
    "challenge-target",
    "challenge-status",
    "challenge-list",
    "challenge-count-pill",
    "open-current-match",
    "user-list",
    "match-list",
    "announcement-list",
    "user-count-pill",
    "match-count-pill",
    "match-title",
    "match-action-hint",
    "score-player-1",
    "score-player-2",
    "match-status-message",
    "opponent-hand",
    "opponent-expeditions",
    "discard-piles",
    "draw-pile",
    "player-expeditions",
    "player-hand",
    "action-form",
    "action-card-id",
    "action-type",
    "action-suit",
    "action-status",
    "clear-board-selection",
    "poll-match",
    "replay-prev",
    "replay-next",
    "replay-status",
    "replay-log",
    "simulation-form",
    "sim-agent-a",
    "sim-agent-b",
    "sim-rounds",
    "simulation-status",
    "simulation-summary",
    "replay-create-form",
    "replay-agent-a",
    "replay-agent-b",
    "replay-create-status",
    "replay-summary",
    "clear-results",
    "simulation-results",
    "docs-download-link",
  ];
  ids.forEach((id) => {
    dom[id] = document.getElementById(id);
  });
  dom.tabButtons = Array.from(document.querySelectorAll(".tab-button"));
  dom.tabPanels = Array.from(document.querySelectorAll(".tab-panel"));
  dom.agentCardTemplate = document.getElementById("agent-card-template");
}

function bindEvents() {
  dom.tabButtons.forEach((button) => {
    button.addEventListener("click", () => activateTab(button.dataset.tabTarget));
  });
  dom["refresh-all"].addEventListener("click", () => refreshAll());
  dom["refresh-agents"].addEventListener("click", () => refreshAgents());
  dom["join-lobby"].addEventListener("click", () => joinLobby());
  dom["refresh-lobby"].addEventListener("click", () => refreshLobby());
  dom["challenge-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    createMatchFromForm();
  });
  dom["challenge-mode"].addEventListener("change", () => populateChallengeTargets());
  dom["open-current-match"].addEventListener("click", async () => {
    clearBoardSelection();
    state.currentReplay = null;
    activateTab("match");
    await refreshCurrentMatch();
  });
  dom["action-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    submitAction();
  });
  dom["poll-match"].addEventListener("click", () => refreshCurrentMatch());
  dom["clear-board-selection"].addEventListener("click", () => clearBoardSelection());
  dom["simulation-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    runSimulation();
  });
  dom["replay-create-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    createReplay();
  });
  dom["clear-results"].addEventListener("click", () => {
    dom["simulation-results"].innerHTML = "";
    dom["simulation-summary"].innerHTML = "";
    dom["replay-summary"].innerHTML = "";
  });
  dom["replay-prev"].addEventListener("click", () => moveReplay(-1));
  dom["replay-next"].addEventListener("click", () => moveReplay(1));
  dom["agent-upload-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    uploadAgent();
  });
}

async function bootstrapUi() {
  dom["player-name"].value = state.session.playerName;
  dom["docs-download-link"].href = `${state.config.apiBase}/agents/api-markdown`;
  await refreshAll();
  window.setInterval(() => refreshLobby().catch(() => {}), 5000);
  window.setInterval(() => {
    if (state.session.currentMatchId && !state.currentReplay) {
      refreshCurrentMatch().catch(() => {});
    }
  }, 4000);
}

async function refreshAll() {
  await Promise.allSettled([
    refreshServerInfo(),
    refreshAgents(),
    refreshLobby(),
  ]);
  if (state.session.currentMatchId && !state.currentReplay) {
    await refreshCurrentMatch();
  }
}

async function refreshServerInfo() {
  try {
    const payload = await apiRequest("/server/info");
    const info = payload.server || payload;
    state.serverInfo = info;
    dom["server-status"].textContent = payload.status || "online";
    dom["server-url"].textContent = payload.lan_url || window.location.origin;
    const users = payload.connected_users || [];
    dom["connected-users"].textContent = String(users.length);
  } catch (error) {
    dom["server-status"].textContent = "offline";
    dom["server-url"].textContent = window.location.origin;
  }
}

async function refreshAgents() {
  try {
    const payload = await apiRequest("/agents");
    state.agents = payload.agents || [];
    renderAgents();
    populateAgentSelectors();
    populateChallengeTargets();
    setStatus(dom["agent-upload-status"], `Loaded ${state.agents.length} agents.`);
  } catch (error) {
    renderEmpty(dom["agent-list"], "Agent registry unavailable.");
    setStatus(dom["agent-upload-status"], error.message, true);
  }
}

async function refreshLobby() {
  try {
    const previousMatchId = state.session.currentMatchId;
    const path = state.session.userToken
      ? `/lobby?user_token=${encodeURIComponent(state.session.userToken)}`
      : "/lobby";
    const payload = await apiRequest(path);
    state.users = payload.users || [];
    state.challenges = payload.challenges || [];
    state.announcements = payload.announcements || [];
    state.matches = payload.matches || [];
    if (payload.user) {
      state.session.userId = payload.user.id || state.session.userId;
      if (payload.user.matchId) {
        state.session.currentMatchId = payload.user.matchId;
      }
      persistSession();
    }
    renderUsers();
    renderMatches();
    renderChallenges();
    renderAnnouncements();
    populateChallengeTargets();
    if (state.session.currentMatchId && state.session.currentMatchId !== previousMatchId) {
      state.currentReplay = null;
      setStatus(dom["challenge-status"], "A challenge was accepted. Open current match to continue.");
      await refreshCurrentMatch();
    }
  } catch (error) {
    renderEmpty(dom["user-list"], "Lobby endpoint unavailable.");
    renderEmpty(dom["match-list"], "Match list unavailable.");
    renderEmpty(dom["challenge-list"], "Challenge list unavailable.");
    renderEmpty(dom["announcement-list"], "Announcements unavailable.");
  }
}

async function joinLobby() {
  const playerName = dom["player-name"].value.trim();
  if (!playerName) {
    setStatus(dom["session-status"], "Enter a player name first.", true);
    return;
  }
  try {
    const payload = await apiRequest("/lobby/join", {
      method: "POST",
      body: { player_name: playerName },
    });
    state.session.playerName = payload.name || playerName;
    state.session.userId = payload.userId || state.session.userId;
    state.session.userToken = payload.userToken || state.session.userToken;
    clearBoardSelection();
    persistSession();
    setStatus(dom["session-status"], `Joined lobby as ${state.session.playerName}.`);
    await refreshLobby();
  } catch (error) {
    setStatus(dom["session-status"], error.message, true);
  }
}

async function createMatchFromForm() {
  if (!state.session.userToken) {
    setStatus(dom["challenge-status"], "Join the lobby first.", true);
    return;
  }
  const mode = dom["challenge-mode"].value;
  const target = dom["challenge-target"].value;
  const usePurple = dom["use-purple"].checked;
  if (!target) {
    setStatus(dom["challenge-status"], "Choose an opponent or AI first.", true);
    return;
  }
  try {
    const targetLabel = dom["challenge-target"].selectedOptions?.[0]?.textContent || target;
    if (mode === "human-human") {
      await apiRequest("/challenges/human", {
        method: "POST",
        body: {
          userToken: state.session.userToken,
          targetUserId: target,
          usePurple,
        },
      });
      setStatus(dom["challenge-status"], `Challenge sent to ${targetLabel}.`);
    } else {
      const payload = await apiRequest("/matches/versus-ai", {
        method: "POST",
        body: {
          userToken: state.session.userToken,
          agentId: target,
          usePurple,
        },
      });
      const match = payload.match;
      if (match?.id) {
        state.currentReplay = null;
        state.session.currentMatchId = match.id;
        state.currentMatch = match;
        clearBoardSelection();
        persistSession();
        renderMatch();
        activateTab("match");
      }
      setStatus(dom["challenge-status"], "Human vs AI match created.");
    }
    await refreshLobby();
  } catch (error) {
    setStatus(dom["challenge-status"], error.message, true);
  }
}

async function acceptChallenge(challengeId) {
  if (!state.session.userToken) {
    setStatus(dom["challenge-status"], "Join the lobby first.", true);
    return;
  }
  try {
    const payload = await apiRequest("/challenges/accept", {
      method: "POST",
      body: {
        userToken: state.session.userToken,
        challengeId,
      },
    });
    const match = payload.match;
    if (match?.id) {
      state.currentReplay = null;
      state.session.currentMatchId = match.id;
      state.currentMatch = match;
      clearBoardSelection();
      persistSession();
      renderMatch();
      activateTab("match");
    }
    setStatus(dom["challenge-status"], "Challenge accepted. Match ready.");
    await refreshLobby();
  } catch (error) {
    setStatus(dom["challenge-status"], error.message, true);
  }
}

async function refreshCurrentMatch() {
  if (!state.session.currentMatchId) {
    renderMatch();
    return;
  }
  try {
    const query = state.session.userToken ? `?user_token=${encodeURIComponent(state.session.userToken)}` : "";
    const payload = await apiRequest(`/matches/${encodeURIComponent(state.session.currentMatchId)}${query}`);
    state.currentMatch = payload.match || payload;
    renderMatch();
  } catch (error) {
    setStatus(dom["action-status"], error.message, true);
  }
}

async function submitAction() {
  if (!state.session.currentMatchId || !state.session.userToken) {
    setStatus(dom["action-status"], "Join the lobby and select a match first.", true);
    return;
  }
  const payload = {
    userToken: state.session.userToken,
    cardId: dom["action-card-id"].value.trim() || state.boardSelection.cardId,
    action: dom["action-type"].value,
    suit: dom["action-suit"].value.trim() || null,
  };
  try {
    const response = await apiRequest(`/matches/${encodeURIComponent(state.session.currentMatchId)}/action`, {
      method: "POST",
      body: payload,
    });
    state.currentMatch = response.match;
    clearBoardSelection();
    renderMatch();
    setStatus(dom["action-status"], "Action submitted.");
  } catch (error) {
    setStatus(dom["action-status"], error.message, true);
  }
}

async function runSimulation() {
  const body = {
    leftAgentId: dom["sim-agent-a"].value,
    rightAgentId: dom["sim-agent-b"].value,
    rounds: Number(dom["sim-rounds"].value || 10),
    usePurple: dom["use-purple"].checked,
  };
  try {
    const response = await apiRequest("/simulations", {
      method: "POST",
      body,
    });
    state.currentSimulation = response.simulation;
    renderSimulation(response.simulation);
    setStatus(dom["simulation-status"], "Simulation completed.");
  } catch (error) {
    setStatus(dom["simulation-status"], error.message, true);
  }
}

async function createReplay() {
  const body = {
    leftAgentId: dom["replay-agent-a"].value,
    rightAgentId: dom["replay-agent-b"].value,
    usePurple: dom["use-purple"].checked,
  };
  try {
    const response = await apiRequest("/replays/ai-match", {
      method: "POST",
      body,
    });
    state.currentReplay = response.replay;
    clearBoardSelection();
    state.replayCursor = 0;
    renderReplay();
    renderReplaySummary(response.replay);
    setStatus(dom["replay-create-status"], "Replay created.");
    activateTab("match");
  } catch (error) {
    setStatus(dom["replay-create-status"], error.message, true);
  }
}

function moveReplay(offset) {
  if (!state.currentReplay?.frames?.length) {
    setStatus(dom["replay-status"], "No replay loaded.", true);
    return;
  }
  state.replayCursor = clamp(state.replayCursor + offset, 0, state.currentReplay.frames.length - 1);
  renderReplay();
}

async function uploadAgent() {
  const file = dom["agent-zip"].files?.[0];
  if (!file) {
    setStatus(dom["agent-upload-status"], "Choose a zip file first.", true);
    return;
  }
  try {
    const response = await fetch(
      `${state.config.apiBase}/agents/upload?filename=${encodeURIComponent(file.name)}`,
      {
        method: "PUT",
        body: await file.arrayBuffer(),
        headers: {
          "Content-Type": "application/zip",
        },
      },
    );
    const payload = await safeJson(response);
    if (!response.ok) {
      throw new Error(payload.error || `Upload failed with ${response.status}`);
    }
    setStatus(dom["agent-upload-status"], `Agent uploaded: ${payload.agent?.name || file.name}.`);
    dom["agent-upload-form"].reset();
    await refreshAgents();
    await refreshLobby();
  } catch (error) {
    setStatus(dom["agent-upload-status"], error.message, true);
  }
}

function renderAgents() {
  if (!state.agents.length) {
    renderEmpty(dom["agent-list"], "No agents loaded yet.");
    return;
  }
  dom["agent-list"].innerHTML = "";
  state.agents.forEach((agent) => {
    const fragment = dom.agentCardTemplate.content.cloneNode(true);
    fragment.querySelector(".agent-type").textContent = agent.origin || "agent";
    fragment.querySelector("h3").textContent = agent.name || agent.id;
    fragment.querySelector(".agent-description").textContent = agent.description || "No description provided.";
    fragment.querySelector(".agent-download").addEventListener("click", () => downloadAgent(agent));
    fragment.querySelector(".agent-challenge").addEventListener("click", () => {
      dom["challenge-mode"].value = "human-ai";
      populateChallengeTargets();
      dom["challenge-target"].value = agent.id;
      activateTab("lobby");
    });
    dom["agent-list"].append(fragment);
  });
}

function renderUsers() {
  dom["connected-users"].textContent = String(state.users.length);
  dom["user-count-pill"].textContent = `${state.users.length} online`;
  if (!state.users.length) {
    renderEmpty(dom["user-list"], "No users connected yet.");
    return;
  }
  dom["user-list"].innerHTML = "";
  state.users.forEach((user) => {
    const card = document.createElement("article");
    card.className = "user-card";
    const suffix = user.id === state.session.userId ? " (you)" : "";
    card.innerHTML = `
      <strong>${escapeHtml(user.name || user.id)}${escapeHtml(suffix)}</strong>
      <p class="subtle">${escapeHtml(user.connected ? "connected" : "idle")}</p>
    `;
    dom["user-list"].append(card);
  });
}

function renderMatches() {
  dom["match-count-pill"].textContent = `${state.matches.length} listed`;
  if (!state.matches.length) {
    renderEmpty(dom["match-list"], "No open matches yet.");
    return;
  }
  dom["match-list"].innerHTML = "";
  state.matches.forEach((match) => {
    const card = document.createElement("article");
    card.className = "match-card";
    const playerNames = Object.values(match.players || {})
      .map((slot) => slot.name || "Seat")
      .join(" vs ");
    const button = document.createElement("button");
    button.className = "ghost-button";
    button.type = "button";
    button.textContent = "Open";
    button.addEventListener("click", async () => {
      state.session.currentMatchId = match.id;
      persistSession();
      activateTab("match");
      await refreshCurrentMatch();
    });
    card.innerHTML = `
      <strong>${escapeHtml(playerNames || match.id)}</strong>
      <p class="subtle">${escapeHtml(match.status || "active")} · ${escapeHtml(match.lastEvent || "")}</p>
    `;
    card.append(button);
    dom["match-list"].append(card);
  });
}

function renderChallenges() {
  const relevant = [...state.challenges]
    .sort((left, right) => Number(right.createdAtEpochMs || 0) - Number(left.createdAtEpochMs || 0))
    .filter((challenge) => {
      if (challenge.status === "pending") {
        return true;
      }
      return challenge.challengerUserId === state.session.userId || challenge.targetUserId === state.session.userId;
    });
  const pendingCount = relevant.filter((challenge) => challenge.status === "pending").length;
  dom["challenge-count-pill"].textContent = `${pendingCount} pending`;
  if (!relevant.length) {
    renderEmpty(dom["challenge-list"], "No human challenges yet.");
    return;
  }
  dom["challenge-list"].innerHTML = "";
  relevant.forEach((challenge) => {
    const isIncoming = challenge.targetUserId === state.session.userId;
    const isOutgoing = challenge.challengerUserId === state.session.userId;
    const card = document.createElement("article");
    card.className = "challenge-card";
    const heading = isIncoming
      ? `Incoming from ${challenge.challengerName}`
      : isOutgoing
        ? `Outgoing to ${challenge.targetName}`
        : `${challenge.challengerName} challenged ${challenge.targetName}`;
    const rulesLabel = challenge.rules?.usePurple ? "purple variant" : "standard rules";
    card.innerHTML = `
      <strong>${escapeHtml(heading)}</strong>
      <p class="subtle">${escapeHtml(challenge.status || "pending")} · ${escapeHtml(rulesLabel)}</p>
    `;
    const actions = document.createElement("div");
    actions.className = "button-row compact";
    if (challenge.status === "pending" && isIncoming) {
      const acceptButton = document.createElement("button");
      acceptButton.type = "button";
      acceptButton.textContent = "Accept";
      acceptButton.addEventListener("click", () => acceptChallenge(challenge.id));
      actions.append(acceptButton);
    }
    if (challenge.acceptedMatchId) {
      const openButton = document.createElement("button");
      openButton.type = "button";
      openButton.className = "ghost-button";
      openButton.textContent = "Open match";
      openButton.addEventListener("click", async () => {
        state.session.currentMatchId = challenge.acceptedMatchId;
        persistSession();
        activateTab("match");
        await refreshCurrentMatch();
      });
      actions.append(openButton);
    }
    if (actions.childNodes.length) {
      card.append(actions);
    }
    dom["challenge-list"].append(card);
  });
}

function renderAnnouncements() {
  const items = [...state.announcements].sort(
    (left, right) => Number(right.createdAtEpochMs || 0) - Number(left.createdAtEpochMs || 0),
  );
  if (!items.length) {
    renderEmpty(dom["announcement-list"], "No announcements yet.");
    return;
  }
  dom["announcement-list"].innerHTML = "";
  items.forEach((entry) => {
    const card = document.createElement("article");
    card.className = "announcement-card";
    card.innerHTML = `
      <strong>${escapeHtml(entry.message || "Update")}</strong>
    `;
    dom["announcement-list"].append(card);
  });
}

function renderMatch() {
  const match = state.currentMatch;
  if (!match) {
    dom["match-title"].textContent = "No active match";
    dom["match-action-hint"].textContent = "Join or create a match from the lobby.";
    dom["match-status-message"].textContent = "Join or create a match from the lobby.";
    ["opponent-hand", "opponent-expeditions", "discard-piles", "draw-pile", "player-expeditions", "player-hand"].forEach((key) => {
      dom[key].innerHTML = "";
    });
    return;
  }

  const me = inferViewerSeat(match);
  const opp = me === 1 ? 2 : 1;
  const activeSuits = inferSuits(match);
  const lost = match.lostCities || {};
  const phase = lost.phase || "";
  const isMyTurn = match.status === "active" && lost.turnPlayer === me;
  const canInteract = isMyTurn && !state.currentReplay;
  const players = match.players || {};
  const meEntry = players[String(me)] || players[me] || {};
  const oppEntry = players[String(opp)] || players[opp] || {};
  const meSlot = meEntry.slot || {};
  const oppSlot = oppEntry.slot || {};
  const meState = meEntry.state || {};
  const oppState = oppEntry.state || {};
  const handCardIds = new Set(Array.isArray(meState.hand) ? meState.hand : []);
  if (state.boardSelection.cardId && (!handCardIds.has(state.boardSelection.cardId) || !canInteract)) {
    clearBoardSelection();
  }
  const selectedCardId = state.boardSelection.cardId;
  const selectedSuit = selectedCardId ? cardSuitFromId(selectedCardId) : "";
  let hint = "Join or create a match from the lobby.";
  if (state.currentReplay) {
    hint = "Replay mode is open. Return to the live match to play.";
  } else if (!isMyTurn) {
    hint = match.status === "active" ? `Waiting for ${oppSlot.name || "your opponent"} to move.` : "Match is not active.";
  } else if (phase === "play") {
    hint = selectedCardId
      ? `Selected ${formatCardLabel(selectedCardId)}. Click its expedition lane to play or its discard pile to discard.`
      : "Click a card in your hand, then click the board target to play or discard it.";
  } else if (phase === "draw") {
    hint = "Click the draw pile or any discard pile to finish your turn.";
  }

  dom["match-title"].textContent = `P${me} ${meSlot.name || "You"} vs P${opp} ${oppSlot.name || "Opponent"}`;
  dom["match-action-hint"].textContent = hint;
  dom["match-status-message"].textContent = match.lastEvent || match.status || "Match loaded.";
  dom["score-player-1"].textContent = String(match.score?.[String(me)] ?? match.score?.[me] ?? 0);
  dom["score-player-2"].textContent = String(match.score?.[String(opp)] ?? match.score?.[opp] ?? 0);

  const hiddenOpponentCards = Array.from({ length: oppState.handCount || 0 }, () => ({ hidden: true }));
  const visibleOpponentCards = Array.isArray(oppState.hand) ? oppState.hand.map((cardId) => ({ cardId })) : hiddenOpponentCards;
  renderHand(dom["opponent-hand"], visibleOpponentCards);
  renderLaneGrid(dom["opponent-expeditions"], activeSuits, (suit) => oppState.expeditions?.[suit] || []);
  renderDiscardPiles(dom["discard-piles"], activeSuits, lost.discardPiles || {});
  renderDrawPile(dom["draw-pile"], Number(lost.deckCount || 0));
  renderLaneGrid(dom["player-expeditions"], activeSuits, (suit) => meState.expeditions?.[suit] || [], {
    selectedSuit,
    canInteract,
    phase,
    selectedCardId,
  });
  renderHand(dom["player-hand"], (meState.hand || []).map((cardId) => ({ cardId })), {
    selectable: canInteract && phase === "play",
    selectedCardId,
  });
}

function renderLaneGrid(container, suits, cardsForSuit, options = {}) {
  container.innerHTML = "";
  suits.forEach((suit) => {
    const lane = document.createElement("section");
    lane.className = "lane-stack";
    const isTargetLane = Boolean(options.canInteract && options.phase === "play" && options.selectedCardId && options.selectedSuit === suit);
    if (isTargetLane) {
      lane.classList.add("is-target-active");
      lane.tabIndex = 0;
      lane.setAttribute("role", "button");
      lane.setAttribute("aria-label", `Play selected card to ${suit}`);
      lane.addEventListener("click", () => {
        submitBoardAction({
          action: "play_expedition",
          cardId: options.selectedCardId,
          suit,
        });
      });
      lane.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          submitBoardAction({
            action: "play_expedition",
            cardId: options.selectedCardId,
            suit,
          });
        }
      });
    }
    const title = document.createElement("p");
    title.className = "lane-title";
    title.textContent = capitalize(suit);
    if (isTargetLane) {
      const tag = document.createElement("span");
      tag.className = "lane-callout";
      tag.textContent = "Play here";
      title.append(tag);
    }
    const cards = cardsForSuit(suit);
    lane.append(title, buildExpeditionColumn(cards, suit));
    container.append(lane);
  });
}

function renderDiscardPiles(container, suits, discardPiles) {
  container.innerHTML = "";
  suits.forEach((suit) => {
    const lane = document.createElement("section");
    lane.className = "discard-stack";
    const canDiscard = Boolean(
      !state.currentReplay
      && state.currentMatch
      && state.currentMatch.status === "active"
      && inferViewerSeat(state.currentMatch) === (state.currentMatch.lostCities?.turnPlayer || 1)
    );
    const phase = state.currentMatch?.lostCities?.phase || "";
    const selectedCardId = state.boardSelection.cardId;
    const selectedSuit = selectedCardId ? cardSuitFromId(selectedCardId) : "";
    const isPlayDiscardTarget = Boolean(canDiscard && phase === "play" && selectedCardId && selectedSuit === suit);
    const isDrawTarget = Boolean(canDiscard && phase === "draw");
    const isTargetLane = isPlayDiscardTarget || isDrawTarget;
    if (isTargetLane) {
      lane.classList.add("is-target-active");
      lane.tabIndex = 0;
      lane.setAttribute("role", "button");
      lane.setAttribute("aria-label", isPlayDiscardTarget ? `Discard selected card to ${suit}` : `Draw from ${suit} discard`);
      lane.addEventListener("click", () => {
        if (phase === "play") {
          submitBoardAction({
            action: "discard",
            cardId: selectedCardId,
            suit,
          });
        } else {
          submitBoardAction({
            action: "draw_discard",
            suit,
          });
        }
      });
      lane.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          if (phase === "play") {
            submitBoardAction({
              action: "discard",
              cardId: selectedCardId,
              suit,
            });
          } else {
            submitBoardAction({
              action: "draw_discard",
              suit,
            });
          }
        }
      });
    }
    const title = document.createElement("p");
    title.className = "lane-title";
    title.textContent = capitalize(suit);
    if (isPlayDiscardTarget) {
      const tag = document.createElement("span");
      tag.className = "lane-callout";
      tag.textContent = "Discard here";
      title.append(tag);
    } else if (isDrawTarget) {
      const tag = document.createElement("span");
      tag.className = "lane-callout";
      tag.textContent = "Draw here";
      title.append(tag);
    }
    const pile = discardPiles[suit] || [];
    lane.append(title, buildDiscardSummary(pile, suit));
    container.append(lane);
  });
}

function renderDrawPile(container, deckCount) {
  container.innerHTML = "";
  const isDrawPhase = Boolean(
    !state.currentReplay
    && state.currentMatch
    && state.currentMatch.status === "active"
    && inferViewerSeat(state.currentMatch) === (state.currentMatch.lostCities?.turnPlayer || 1)
    && state.currentMatch.lostCities?.phase === "draw"
  );
  const drawBlock = container.parentElement;
  const drawLabel = drawBlock?.querySelector(".draw-label");
  if (drawLabel) {
    drawLabel.textContent = isDrawPhase ? "Draw pile • draw here" : "Draw pile";
  }
  if (drawBlock) {
    drawBlock.classList.toggle("is-target-active", isDrawPhase && deckCount > 0);
    if (isDrawPhase && deckCount > 0) {
      drawBlock.setAttribute("role", "button");
      drawBlock.setAttribute("tabindex", "0");
      drawBlock.setAttribute("aria-label", "Draw from deck");
    } else {
      drawBlock.removeAttribute("role");
      drawBlock.removeAttribute("tabindex");
      drawBlock.removeAttribute("aria-label");
    }
    drawBlock.onclick = isDrawPhase && deckCount > 0 ? () => submitBoardAction({ action: "draw_deck" }) : null;
    drawBlock.onkeydown = isDrawPhase && deckCount > 0
      ? (event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            submitBoardAction({ action: "draw_deck" });
          }
        }
      : null;
  }
  const card = buildCard({ hidden: deckCount > 0 }, { selectable: false });
  if (deckCount <= 0) {
    container.append(buildEmptySlot("Empty"));
    return;
  }
  container.append(card);
  const count = document.createElement("span");
  count.className = "pill";
  count.textContent = `${deckCount} left`;
  container.append(count);
}

function renderHand(container, cards, options = false) {
  const normalizedOptions = typeof options === "boolean" ? { selectable: options } : options;
  container.innerHTML = "";
  if (!cards.length) {
    container.append(buildEmptySlot("No cards"));
    return;
  }
  cards.forEach((card) => {
    container.append(buildCard(card, {
      ...normalizedOptions,
      selected: normalizedOptions.selectedCardId ? normalizedOptions.selectedCardId === card.cardId : false,
    }));
  });
}

function buildExpeditionColumn(cards, suit) {
  const column = document.createElement("div");
  column.className = "expedition-column";
  if (!cards.length) {
    const empty = document.createElement("div");
    empty.className = `expedition-empty ${suitClassName(suit)}`;
    empty.textContent = "No cards";
    column.append(empty);
    return column;
  }

  const history = document.createElement("div");
  history.className = "expedition-history";
  cards.slice(0, -1).forEach((cardId) => {
    history.append(buildExpeditionStrip(cardId));
  });
  if (history.childNodes.length) {
    column.append(history);
  }

  const topCard = document.createElement("div");
  topCard.className = "expedition-top-card";
  topCard.append(buildCard({ cardId: cards[cards.length - 1] }));
  column.append(topCard);
  return column;
}

function buildExpeditionStrip(cardId) {
  const strip = document.createElement("div");
  strip.className = `expedition-strip ${suitClassName(cardSuitFromId(cardId))}`;
  const value = document.createElement("strong");
  value.textContent = cardStripLabel(cardId);
  strip.append(value);
  return strip;
}

function buildDiscardSummary(pile, suit) {
  const summary = document.createElement("div");
  summary.className = "discard-summary";
  if (!pile.length) {
    summary.append(buildEmptySlot("Empty"));
  } else {
    summary.append(buildCard({ cardId: pile[pile.length - 1] }));
  }
  const count = document.createElement("div");
  count.className = `discard-count ${suitClassName(suit)}`;
  count.innerHTML = `<strong>${pile.length}</strong><span>${pile.length === 1 ? "card" : "cards"}</span>`;
  summary.append(count);
  return summary;
}

function renderReplay() {
  const replay = state.currentReplay;
  if (!replay?.frames?.length) {
    dom["replay-status"].textContent = "No replay loaded.";
    dom["replay-log"].innerHTML = "";
    return;
  }
  const frame = replay.frames[state.replayCursor];
  dom["replay-status"].textContent = `Frame ${state.replayCursor + 1} / ${replay.frames.length}`;
  dom["replay-log"].innerHTML = "";
  replay.frames.forEach((entry, index) => {
    const line = document.createElement("article");
    line.className = "replay-line";
    if (index === state.replayCursor) {
      line.style.borderColor = "rgba(240, 189, 82, 0.7)";
    }
    line.innerHTML = `
      <strong>${escapeHtml(entry.label || `Frame ${index + 1}`)}</strong>
      <p class="subtle">${escapeHtml(entry.event || "")}</p>
    `;
    dom["replay-log"].append(line);
  });
  if (frame.state) {
    state.currentMatch = frame.state;
    renderMatch();
  }
}

function renderReplaySummary(replay) {
  dom["replay-summary"].innerHTML = "";
  const info = [
    `Replay: ${replay.label}`,
    `Winner: ${replay.winner}`,
    `Final score P1 ${replay.finalScores?.["1"] ?? replay.finalScores?.[1] ?? 0} / P2 ${replay.finalScores?.["2"] ?? replay.finalScores?.[2] ?? 0}`,
  ];
  info.forEach((item) => {
    const line = document.createElement("article");
    line.className = "result-card";
    line.textContent = item;
    dom["replay-summary"].append(line);
  });
}

function renderSimulation(simulation) {
  const summary = simulation.aggregate || {};
  dom["simulation-summary"].innerHTML = "";
  Object.entries(summary).forEach(([key, value]) => {
    const tile = document.createElement("article");
    tile.className = "metric-tile";
    tile.innerHTML = `<span>${escapeHtml(key)}</span><strong>${escapeHtml(String(value))}</strong>`;
    dom["simulation-summary"].append(tile);
  });
  dom["simulation-results"].innerHTML = "";
  (simulation.results || []).forEach((item) => {
    const card = document.createElement("article");
    card.className = "result-card";
    const replayLine = item.replayId
      ? `<p class="subtle">Replay: ${escapeHtml(item.replayId || "")}</p>`
      : "";
    card.innerHTML = `
      <strong>Round ${escapeHtml(String(item.roundIndex))}</strong>
      <p class="subtle">Winner: ${escapeHtml(String(item.winner))}</p>
      <p class="subtle">Scores: P1 ${escapeHtml(String(item.scores?.["1"] ?? item.scores?.[1] ?? 0))} / P2 ${escapeHtml(String(item.scores?.["2"] ?? item.scores?.[2] ?? 0))}</p>
      ${replayLine}
    `;
    dom["simulation-results"].append(card);
  });
}

async function downloadAgent(agent) {
  const url = `${state.config.apiBase}/agents/${encodeURIComponent(agent.id)}/download`;
  window.open(url, "_blank", "noopener");
}

function populateAgentSelectors() {
  [
    dom["sim-agent-a"],
    dom["sim-agent-b"],
    dom["replay-agent-a"],
    dom["replay-agent-b"],
  ].forEach((select) => {
    select.innerHTML = "";
    state.agents.forEach((agent) => {
      const option = document.createElement("option");
      option.value = agent.id;
      option.textContent = agent.name || agent.id;
      select.append(option);
    });
  });
}

function populateChallengeTargets() {
  const select = dom["challenge-target"];
  const mode = dom["challenge-mode"].value;
  const previous = select.value;
  select.innerHTML = "";
  const options = mode === "human-human"
    ? state.users.filter((item) => item.id !== state.session.userId)
    : state.agents;
  if (!options.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = mode === "human-human" ? "No other users online" : "No AIs available";
    option.disabled = true;
    option.selected = true;
    select.append(option);
    return;
  }
  options.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = item.name || item.id;
    select.append(option);
  });
  if (options.some((item) => item.id === previous)) {
    select.value = previous;
  }
}

function buildCard(card, options = false) {
  const normalizedOptions = typeof options === "boolean" ? { selectable: options } : (options || {});
  const wrapper = document.createElement("div");
  wrapper.className = "web-card";
  const button = document.createElement("button");
  button.type = "button";

  if (card.hidden) {
    const img = document.createElement("img");
    img.alt = "Hidden card";
    img.src = `${state.config.assetBase}/cards/png/card_back.png`;
    img.onerror = () => {
      button.innerHTML = `<div class="card-fallback">Hidden card</div>`;
    };
    button.append(img);
  } else {
    const img = document.createElement("img");
    img.alt = card.cardId;
    img.src = cardAssetPath(card.cardId);
    img.onerror = () => {
      button.innerHTML = `<div class="card-fallback">${escapeHtml(formatCardLabel(card.cardId))}</div>`;
    };
    button.append(img);
  }

  if (normalizedOptions.selectable && !card.hidden && card.cardId) {
    wrapper.classList.add("is-selectable");
    button.addEventListener("click", () => {
      if (state.boardSelection.cardId === card.cardId) {
        clearBoardSelection();
        return;
      }
      selectBoardCard(card.cardId);
      renderMatch();
    });
  }

  if (normalizedOptions.selected) {
    wrapper.classList.add("is-selected");
  }

  if (typeof normalizedOptions.onClick === "function") {
    wrapper.classList.add("is-target-active");
    wrapper.setAttribute("role", "button");
    wrapper.setAttribute("tabindex", "0");
    wrapper.addEventListener("click", normalizedOptions.onClick);
    wrapper.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        normalizedOptions.onClick(event);
      }
    });
  }

  wrapper.append(button);
  return wrapper;
}

function buildEmptySlot(label = "") {
  const slot = document.createElement("div");
  slot.className = "card-slot";
  if (label) {
    slot.textContent = label;
    slot.style.display = "grid";
    slot.style.placeItems = "center";
    slot.style.color = "var(--muted-text)";
  }
  return slot;
}

function activateTab(target) {
  dom.tabButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.tabTarget === target);
  });
  dom.tabPanels.forEach((panel) => {
    panel.classList.toggle("is-active", panel.id === `tab-${target}`);
  });
}

function cardSuitFromId(cardId) {
  return String(cardId || "").split("_", 1)[0];
}

function suitClassName(suit) {
  const normalized = String(suit || "").trim().toLowerCase();
  return normalized ? `suit-${normalized}` : "";
}

function cardStripLabel(cardId) {
  const normalized = String(cardId || "").trim().toLowerCase();
  if (normalized.includes("wager") || normalized.includes("investment")) {
    return "x";
  }
  const tail = normalized.split("_").at(-1) || "";
  const rank = Number(tail);
  return Number.isFinite(rank) && rank > 0 ? String(rank) : "?";
}

function selectBoardCard(cardId) {
  state.boardSelection.cardId = cardId;
  dom["action-card-id"].value = cardId;
  dom["action-suit"].value = cardSuitFromId(cardId);
  dom["action-type"].value = "play_expedition";
}

function clearBoardSelection() {
  state.boardSelection.cardId = "";
  if (dom["action-card-id"]) {
    dom["action-card-id"].value = "";
  }
  if (dom["action-suit"]) {
    dom["action-suit"].value = "";
  }
}

async function submitBoardAction(body) {
  if (!state.session.currentMatchId || !state.session.userToken) {
    setStatus(dom["action-status"], "Join the lobby and select a match first.", true);
    return;
  }
  try {
    const response = await apiRequest(`/matches/${encodeURIComponent(state.session.currentMatchId)}/action`, {
      method: "POST",
      body: {
        userToken: state.session.userToken,
        action: body.action,
        cardId: body.cardId || "",
        suit: body.suit || null,
      },
    });
    state.currentMatch = response.match;
    clearBoardSelection();
    renderMatch();
    setStatus(dom["action-status"], "Action submitted.");
  } catch (error) {
    setStatus(dom["action-status"], error.message, true);
  }
}

function setStatus(node, message, isError = false) {
  node.textContent = message;
  node.style.color = isError ? "var(--red)" : "var(--muted-text)";
}

function renderEmpty(node, message) {
  node.innerHTML = `<p class="empty-state">${escapeHtml(message)}</p>`;
}

async function apiRequest(path, options = {}) {
  const request = {
    method: options.method || "GET",
    headers: {},
  };
  if (options.body) {
    request.headers["Content-Type"] = "application/json";
    request.body = JSON.stringify(options.body);
  }
  const response = await fetch(`${state.config.apiBase}${path}`, request);
  const payload = await safeJson(response);
  if (!response.ok) {
    throw new Error(payload.error || `Request failed with ${response.status}`);
  }
  return payload;
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch (error) {
    return {};
  }
}

function inferViewerSeat(match) {
  const entries = Object.entries(match.players || {});
  const named = entries.find(([, player]) => {
    const slot = player.slot || {};
    return slot.userId && slot.userId === state.session.userId;
  });
  return Number(named?.[0] || 1);
}

function inferSuits(match) {
  const lost = match.lostCities || {};
  return lost.activeSuits || ((match.rules?.usePurple || match.rules?.use_purple)
    ? ["yellow", "white", "blue", "green", "red", "purple"]
    : ["yellow", "white", "blue", "green", "red"]);
}

function cardAssetPath(cardId) {
  if (!cardId) {
    return `${state.config.assetBase}/cards/png/card_back.png`;
  }
  return `${state.config.assetBase}/cards/png/${normalizeCardId(cardId)}.png`;
}

function normalizeCardId(cardId) {
  const [suit, rank, maybeSerial] = String(cardId).split("_");
  if (rank === "wager") {
    return `${suit}_wager_${maybeSerial}`;
  }
  if (!rank) {
    return String(cardId);
  }
  return `${suit}_${String(Number(rank)).padStart(2, "0")}`;
}

function formatCardLabel(cardId) {
  return String(cardId)
    .replaceAll("_", " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function capitalize(value) {
  return value ? value.charAt(0).toUpperCase() + value.slice(1) : "";
}

function persistSession() {
  localStorage.setItem("lostCities.playerName", state.session.playerName);
  localStorage.setItem("lostCities.userId", state.session.userId);
  localStorage.setItem("lostCities.userToken", state.session.userToken);
  localStorage.setItem("lostCities.currentMatchId", state.session.currentMatchId);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
