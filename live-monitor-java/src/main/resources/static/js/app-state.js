const page = document.body.dataset.page;
let dashboardState = {
  services: [],
  recentResults: [],
  alertGroups: [],
  serviceGroups: [],
  expandedServiceGroupKeys: new Set(),
  expandedInitialized: false,
  filter: "all",
  query: "",
  instanceQueries: {},
  instancePages: {},
  groupStatusFilters: {},
  dismissedActivityKeys: new Set(),
  lastActivityCandidates: [],
  lastActivityRows: [],
};
let alertSettingsState = {
  services: [],
  groups: [],
  policies: [],
  channels: [],
  alerts: [],
  selectedGroupId: null,
  selectedChannelId: null,
  bindingServiceIds: [],
  testResults: {},
  busyActions: {},
  filters: {
    status: "all",
    serviceType: "all",
    query: "",
  },
};
let hostState = {
  hosts: [],
  processes: [],
  processStatus: {},
  alertGroups: [],
  selectedHostId: null,
  metrics: null,
  metricTimer: null,
  listMetricTimer: null,
  listMetricSnapshots: {},
  metricHistory: {
    cpu: [],
    load: [],
    memory: [],
    disk: [],
  },
};
let notificationState = {
  alerts: [],
  seenAlertId: Number(localStorage.getItem("liveMonitorSeenAlertId") || 0),
  poller: null,
};
let resourceMetricState = {
  history: {
    cpu: [],
    memory: [],
    disk: [],
    network: [],
  },
  poller: null,
};


