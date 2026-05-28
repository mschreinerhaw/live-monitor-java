(function () {
  function statusValue(status) {
    if (status === "UP") return 1;
    if (status === "DOWN") return -1;
    return 0;
  }

  function renderTrendChart(element, results) {
    if (!element) return;
    const ordered = [...results].reverse();
    const labels = ordered.map((item) => formatTime(item.checked_at));
    const statusData = ordered.map((item) => statusValue(item.status));
    const responseData = ordered.map((item) => item.response_time_ms ?? null);

    if (!window.echarts) {
      element.innerHTML = '<p class="empty">图表资源未加载</p>';
      return;
    }

    const chart = window.echarts.init(element);
    chart.setOption({
      color: ["#176b87", "#168a52"],
      tooltip: {
        trigger: "axis",
        formatter(params) {
          const index = params[0].dataIndex;
          const item = ordered[index];
          return `${labels[index]}<br>状态：${item.status}<br>响应：${item.response_time_ms ?? "-"} ms<br>${item.message || ""}`;
        },
      },
      grid: { top: 24, right: 38, bottom: 38, left: 42 },
      xAxis: {
        type: "category",
        data: labels,
        axisLabel: { color: "#697586" },
        axisLine: { lineStyle: { color: "#d9e1e8" } },
      },
      yAxis: [
        {
          type: "value",
          min: -1,
          max: 1,
          interval: 1,
          axisLabel: {
            color: "#697586",
            formatter(value) {
              if (value === 1) return "UP";
              if (value === -1) return "DOWN";
              return "UNKNOWN";
            },
          },
          splitLine: { lineStyle: { color: "#edf1f5" } },
        },
        {
          type: "value",
          axisLabel: { color: "#697586", formatter: "{value} ms" },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: "状态",
          type: "line",
          step: "end",
          symbolSize: 7,
          data: statusData,
        },
        {
          name: "响应时间",
          type: "bar",
          yAxisIndex: 1,
          barMaxWidth: 20,
          data: responseData,
        },
      ],
    });
    window.addEventListener("resize", () => chart.resize(), { passive: true });
  }

  window.LiveMonitorCharts = { renderTrendChart };
})();
