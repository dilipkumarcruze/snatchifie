// script.js
console.log("JS loaded")
let selectedVideo = null;
let selectedFormat = "mp3";  // Default format

// Set selected format
function setFormat(format) {
	selectedFormat = format;
	document.getElementById("formatSelect").value = format;
}

// Search YouTube videos based on query
function searchVideos() {
	console.log("Hello");
	const query = document.getElementById("searchQuery").value.trim();
	if (!query) {
		alert("Please enter a search term.");
		return;
	}

	localStorage.setItem("lastSearch", query);
	const searchBtn = document.getElementById("searchBtn");
	searchBtn.disabled = true;
	searchBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

	document.getElementById("videoList").innerHTML = "<li class='list-group-item'>Searching...</li>";

	fetch(`/api/youtube/search?query=${encodeURIComponent(query)}`)
		.then(async res => {
			if (!res.ok) {
				const errData = await res.json().catch(() => null);
				throw new Error(errData?.error || "Search failed.");
			}
			return res.json();
		})
		.then(data => {
			const list = document.getElementById("videoList");
			list.innerHTML = "";

			if (data.length === 0) {
				list.innerHTML = "<li class='list-group-item'>No videos found.</li>";
				return;
			}

			data.forEach((item) => {
				const li = document.createElement("li");
				li.className = "list-group-item d-flex justify-content-between align-items-center";

				const titleDiv = document.createElement("div");
				titleDiv.textContent = item.title;
				titleDiv.classList.add("flex-grow-1", "me-3");
				titleDiv.style.cursor = "pointer";

				titleDiv.onclick = () => {
					document.querySelectorAll("#videoList li").forEach(el => el.classList.remove("active"));
					li.classList.add("active");
					selectedVideo = item;
					document.getElementById("downloadSection")?.classList.add("d-none");
					document.getElementById("status")?.innerText = ""; //the line
				};

				const downloadBtn = document.createElement("button");
				downloadBtn.className = "btn btn-success btn-sm";
				downloadBtn.innerHTML = '<i class="fas fa-download"></i>';
				downloadBtn.onclick = () => {
					selectedVideo = item;
					downloadSelected();
				};

				li.appendChild(titleDiv);
				li.appendChild(downloadBtn);
				list.appendChild(li);
			});
		})
		.catch(err => {
			document.getElementById("videoList").innerHTML =
				`<li class='list-group-item text-danger'>Error: ${err.message}</li>`;
		})
		.finally(() => {
			searchBtn.disabled = false;
			searchBtn.innerHTML = '<i class="fas fa-search"></i>';
		});
}

document.getElementById("searchQuery").addEventListener("keydown", function(event) {
	if (event.key === "Enter") {
		event.preventDefault();
		searchVideos();
	}
});

function downloadSelected() {
	if (!selectedVideo) {
		alert("Please select a video.");
		return;
	}

	document.getElementById("modalVideoTitle").innerText = selectedVideo.title;
	document.getElementById("modalStatus").innerHTML = "";
	document.getElementById("downloadProgress").style.width = "0%";
	document.getElementById("downloadProgress").innerText = "0%";

	const modal = new bootstrap.Modal(document.getElementById("downloadModal"));
	modal.show();
}

function startDownload() {
	if (!selectedVideo) {
		alert("Please select a video.");
		return;
	}

	const format = document.getElementById("formatSelect").value;
	const downloadButton = document.getElementById("downloadButton");
	const loadingAnimation = document.getElementById("loadingAnimation");
	const modalStatus = document.getElementById("modalStatus");
	const progressWrap = document.getElementById("progressBarWrap");
	const progressBar = document.getElementById("downloadProgress");

	downloadButton.disabled = true;
	loadingAnimation.classList.remove("d-none");
	modalStatus.innerHTML = "";
	progressWrap.classList.remove("d-none");

	const formData = new URLSearchParams();
	formData.append("videoId", selectedVideo.videoId);
	formData.append("title", selectedVideo.title);
	formData.append("format", format);

	fetch("/api/youtube/download", {
		method: "POST",
		headers: { "Content-Type": "application/x-www-form-urlencoded" },
		body: formData.toString(),
	})
		.then(async response => {
			if (!response.ok) {
				const errData = await response.json().catch(() => null);
				throw new Error(errData?.error || "Failed to download file.");
			}
			return response.blob();
		})
		.then(blob => {
			const url = window.URL.createObjectURL(blob);
			const a = document.createElement("a");
			a.style.display = "none";
			a.href = url;
			a.download = `${selectedVideo.title}.${format}`;
			document.body.appendChild(a);
			a.click();
			window.URL.revokeObjectURL(url);
			document.body.removeChild(a);
			progressBar.style.width = "100%";
			progressBar.innerText = "100%";
			modalStatus.innerHTML = `<span class="text-success">Download completed successfully!</span>`;
		})
		.catch(err => {
			console.error(err);
			modalStatus.innerHTML = `<span class="text-danger">Error: ${err.message}</span>`;
		})
		.finally(() => {
			downloadButton.disabled = false;
			loadingAnimation.classList.add("d-none");
		});
}

window.addEventListener("scroll", function() {
	const logo = document.getElementById("logo");
	if (window.scrollY > 50) {
		logo?.classList.add("fixed");
	} else {
		logo?.classList.remove("fixed");
	}
});

window.addEventListener("load", () => {
	const last = localStorage.getItem("lastSearch");
	if (last) document.getElementById("searchQuery").value = last;
});