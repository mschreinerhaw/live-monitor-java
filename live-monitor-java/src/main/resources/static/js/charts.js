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

    const chart = window.echarts.getInstanceByDom(element) || window.echarts.init(element);
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
      grid: { top: 44, right: 58, bottom: 38, left: 46 },
      xAxis: {
        type: "category",
        data: labels,
        axisLabel: { color: "#697586" },
        axisLine: { lineStyle: { color: "#d9e1e8" } },
      },
      yAxis: [
        {
          type: "value",
          name: "状态",
          min: -1,
          max: 1,
          interval: 1,
          nameTextStyle: { color: "#697586", padding: [0, 0, 6, 0] },
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
          name: "响应时间 (ms)",
          nameTextStyle: { color: "#697586", padding: [0, 0, 6, 0] },
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
          label: {
            show: true,
            position: "top",
            color: "#33455d",
            fontSize: 11,
            formatter(params) {
              return params.value == null ? "" : `${params.value} ms`;
            },
          },
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color(params) {
              if (params.value == null) return "#cbd6df";
              const value = Number(params.value);
              if (!Number.isFinite(value)) return "#cbd6df";
              if (value >= 1000) return "#c0392b";
              if (value >= 500) return "#b7791f";
              return "#168a52";
            },
          },
          data: responseData,
        },
      ],
    });
    window.addEventListener("resize", () => chart.resize(), { passive: true });
  }

  window.LiveMonitorCharts = { renderTrendChart };
})();
